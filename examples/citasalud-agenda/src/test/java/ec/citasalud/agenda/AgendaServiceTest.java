package ec.citasalud.agenda;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pruebas trazables contra el spec de agenda.
 *
 * Cubre FR-001, FR-002, FR-003, FR-004, FR-005 y FR-006.
 */
class AgendaServiceTest {

    private final AgendaService agenda = new AgendaService();
    private static final LocalDateTime FRANJA = LocalDateTime.of(2026, 7, 1, 9, 0);

    @Test
    void reservaFranjaLibre_ok() {
        Reserva r = agenda.reservar("prof-1", FRANJA, "pac-1");
        assertEquals("prof-1", r.profesionalId());
        assertEquals(FRANJA, r.franja());
        assertEquals(1, agenda.listar().size());
    }

    @Test
    void reservaFranjaOcupada_secuencial_rechaza() {
        agenda.reservar("prof-1", FRANJA, "pac-1");
        assertThrows(FranjaOcupadaException.class,
                () -> agenda.reservar("prof-1", FRANJA, "pac-2"));
        assertEquals(1, agenda.listar().size());
    }

    @Test
    void reservaOtraFranja_ok() {
        agenda.reservar("prof-1", FRANJA, "pac-1");
        Reserva otra = agenda.reservar("prof-1", FRANJA.plusHours(1), "pac-2");
        assertNotNull(otra);
        assertEquals(2, agenda.listar().size());
    }

    @Test
    void reservaFranjaOcupada_concurrente_rechaza() throws Exception {
        AgendaService agendaConcurrente = new AgendaService();
        CyclicBarrier barrera = new CyclicBarrier(2);
        CountDownLatch fin = new CountDownLatch(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<Boolean>> resultados = new ArrayList<>();

        for (String paciente : List.of("pac-a", "pac-b")) {
            resultados.add(pool.submit(() -> {
                try {
                    barrera.await();
                    agendaConcurrente.reservar("prof-concurrente", FRANJA.plusDays(1), paciente);
                    return true;
                } catch (FranjaOcupadaException ex) {
                    return false;
                } finally {
                    fin.countDown();
                }
            }));
        }

        fin.await();
        pool.shutdownNow();

        long exitos = 0;
        long rechazadas = 0;
        for (Future<Boolean> resultado : resultados) {
            if (resultado.get()) {
                exitos++;
            } else {
                rechazadas++;
            }
        }

        assertEquals(1, exitos);
        assertEquals(1, rechazadas);
        assertEquals(1, agendaConcurrente.listar().size());
    }

    @Test
    void apiRest_creaYListaReservas() {
        ReservaController controller = new ReservaController(new AgendaService());
        ReservaController.CrearReservaRequest req =
                new ReservaController.CrearReservaRequest("prof-api", FRANJA.plusDays(2), "pac-api");

        ResponseEntity<Reserva> respuesta = controller.crear(req);

        assertEquals(HttpStatus.CREATED, respuesta.getStatusCode());
        assertNotNull(respuesta.getBody());
        assertEquals("prof-api", respuesta.getBody().profesionalId());
        assertEquals(1, controller.listar().size());
    }

    @Test
    void apiRest_franjaOcupada_devuelveConflict() {
        ReservaController controller = new ReservaController(new AgendaService());
        ReservaController.CrearReservaRequest req =
                new ReservaController.CrearReservaRequest("prof-api", FRANJA.plusDays(3), "pac-api");

        controller.crear(req);
        FranjaOcupadaException ex = assertThrows(FranjaOcupadaException.class,
                () -> controller.crear(new ReservaController.CrearReservaRequest(
                        "prof-api", FRANJA.plusDays(3), "pac-otro")));

        ResponseEntity<String> respuesta = controller.manejarOcupada(ex);
        assertEquals(HttpStatus.CONFLICT, respuesta.getStatusCode());
        assertTrue(respuesta.getBody().contains("prof-api"));
    }
}
