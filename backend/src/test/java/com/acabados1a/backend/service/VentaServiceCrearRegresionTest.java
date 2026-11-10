package com.acabados1a.backend.service;

import com.acabados1a.backend.dto.VentaRequest;
import com.acabados1a.backend.model.*;
import com.acabados1a.backend.repository.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

// Pruebas de regresión del hallazgo NC-01 de la auditoría cruzada (VentaService.crear() de ~165
// líneas). Se escribieron ANTES de dividir el método y se corren igual antes y después, para
// demostrar que la refactorización no cambió el comportamiento. Mismo patrón que
// VentaServiceH3VerifyTest: corre contra la BD real (necesita MariaDB) y @Transactional revierte
// todo al terminar - no deja ventas de prueba ni descuenta stock de verdad. EmailService va
// simulado para no mandar correos reales.
@SpringBootTest
@Transactional
class VentaServiceCrearRegresionTest {

    @Autowired VentaService ventaService;
    @Autowired UsuarioRepository usuarioRepository;
    @Autowired InventarioRepository inventarioRepository;
    @Autowired ProductoRepository productoRepository;
    @Autowired DetalleVentaRepository detalleVentaRepository;
    @Autowired PagoRepository pagoRepository;
    @Autowired CotizacionRepository cotizacionRepository;
    @Autowired EntityManager em;

    @MockitoBean EmailService emailService;

    private Usuario cliente;
    private Producto producto;

    @BeforeEach
    void datosBase() {
        cliente = usuarioRepository.findAll().stream()
            .filter(u -> u.getRol() != null && u.getRol().getIdRol() == 2)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("No hay clientes en la BD para probar."));
        Inventario conStock = inventarioRepository.findAll().stream()
            .filter(i -> i.getCantidadDisponible() != null && i.getCantidadDisponible() >= 3)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("No hay inventario con stock para probar."));
        producto = productoRepository.findById(conStock.getIdProducto()).orElseThrow();
    }

    @Test
    void ventaValida_guardaCabeceraDetallePagoYElTriggerDescuentaElStock() {
        int stockAntes = stockReal(producto.getIdProducto());
        // El mismo producto repartido en dos ítems: la validación de stock debe sumarlos.
        VentaRequest request = pedido("tarjeta", "recogida", item(producto, 1), item(producto, 2));

        Venta venta = ventaService.crear(cliente.getEmail(), false, request);

        BigDecimal totalEsperado = producto.getPrecioVenta().multiply(BigDecimal.valueOf(3));
        assertEquals(Venta.Estado.pendiente, venta.getEstado());
        assertFalse(venta.getNumeroVenta().startsWith("TMP-"), "el numero_venta real lo pone el trigger");
        assertEquals(0, totalEsperado.compareTo(venta.getTotal()));
        assertEquals(cliente.getIdUsuario(), venta.getUsuario().getIdUsuario());
        assertEquals(2, detalleVentaRepository.findByVentaIdVenta(venta.getIdVenta()).size());

        List<Pago> pagos = pagoRepository.findByVentaIdVenta(venta.getIdVenta());
        assertEquals(1, pagos.size());
        assertEquals(Pago.Estado.completado, pagos.get(0).getEstado());
        assertEquals(String.format("SIM-%06d", venta.getIdVenta()), pagos.get(0).getTransaccionId());

        assertEquals(stockAntes - 3, stockReal(producto.getIdProducto()), "el trigger descuenta el stock una sola vez");

        ArgumentCaptor<EmailService.DatosConfirmacionPedido> correo = ArgumentCaptor.forClass(EmailService.DatosConfirmacionPedido.class);
        verify(emailService).enviarConfirmacionPedido(eq(cliente.getEmail()), correo.capture());
        assertEquals(venta.getNumeroVenta(), correo.getValue().numeroVenta());
        assertEquals(2, correo.getValue().productos().size());
        assertFalse(correo.getValue().esContraentrega());
        assertFalse(correo.getValue().esAnticipoServicio());
    }

    @Test
    void contraentregaConEnvio_elPagoQuedaPendienteYSinTransaccion() {
        Venta venta = ventaService.crear(cliente.getEmail(), false, pedido("contraentrega", "envio", item(producto, 1)));

        Pago pago = pagoRepository.findByVentaIdVenta(venta.getIdVenta()).get(0);
        assertEquals(Pago.Estado.pendiente, pago.getEstado());
        assertNull(pago.getTransaccionId());
    }

    @Test
    void sinMetodoDeEnvio_seAsumeRecogida() {
        Venta venta = ventaService.crear(cliente.getEmail(), false, pedido("efectivo", null, item(producto, 1)));
        assertEquals(Venta.MetodoEnvio.recogida, venta.getMetodoEnvio());
    }

    @Test
    void contraentregaConRecogida_seRechaza() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> ventaService.crear(cliente.getEmail(), false, pedido("contraentrega", "recogida", item(producto, 1))));
        assertEquals("Contraentrega solo está disponible para envío.", e.getMessage());
    }

    @Test
    void metodoDePagoInvalido_seRechaza() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> ventaService.crear(cliente.getEmail(), false, pedido("bitcoin", "recogida", item(producto, 1))));
        assertEquals("El método de pago indicado no es válido.", e.getMessage());
    }

    @Test
    void stockInsuficiente_seRechazaSinInsertarNingunaVenta() {
        long ventasAntes = contarVentas();
        int stock = stockReal(producto.getIdProducto());

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> ventaService.crear(cliente.getEmail(), false, pedido("tarjeta", "recogida", item(producto, stock + 1))));

        assertTrue(e.getMessage().startsWith("No hay suficiente stock de"), e.getMessage());
        assertEquals(ventasAntes, contarVentas(), "no debe quedar ninguna cabecera de venta insertada");
        verify(emailService, never()).enviarConfirmacionPedido(anyString(), org.mockito.ArgumentMatchers.any());
    }

    // Revisión pre-sustentación H1 (10/11/2026): un producto eliminado (borrado lógico) no sale en
    // el catálogo, pero antes se podía comprar entrando por su enlace directo.
    @Test
    void productoEliminado_seRechazaSinInsertarNingunaVenta() {
        producto.setActivo(false);
        productoRepository.saveAndFlush(producto); // @Transactional lo revierte al terminar
        long ventasAntes = contarVentas();

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> ventaService.crear(cliente.getEmail(), false, pedido("tarjeta", "recogida", item(producto, 1))));

        assertEquals("El producto \"" + producto.getNombre() + "\" ya no está disponible.", e.getMessage());
        assertEquals(ventasAntes, contarVentas(), "no debe quedar ninguna cabecera de venta insertada");
        verify(emailService, never()).enviarConfirmacionPedido(anyString(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void unClienteNoPuedeComprarANombreDeOtro() {
        Usuario otro = usuarioRepository.findAll().stream()
            .filter(u -> !u.getIdUsuario().equals(cliente.getIdUsuario()))
            .findFirst().orElseThrow();
        VentaRequest request = pedido("tarjeta", "recogida", item(producto, 1));
        request.setIdUsuario(otro.getIdUsuario());

        Venta venta = ventaService.crear(cliente.getEmail(), false, request);

        assertEquals(cliente.getIdUsuario(), venta.getUsuario().getIdUsuario(), "el id_usuario del body se ignora");
    }

    @Test
    void unClienteNoPuedeAsociarUnaCotizacionAjena() {
        Cotizacion ajena = cotizacionRepository.findAll().stream()
            .filter(c -> !c.getUsuario().getIdUsuario().equals(cliente.getIdUsuario()))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("No hay cotizaciones de otro usuario para probar."));
        VentaRequest request = pedido("tarjeta", "recogida");
        request.setIdCotizacion(ajena.getIdCotizacion());
        request.setTotal(BigDecimal.TEN);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> ventaService.crear(cliente.getEmail(), false, request));
        assertEquals("No tienes permiso para asociar esa cotización a la venta.", e.getMessage());
    }

    @Test
    void anticipoDeCotizacionSinItems_elCorreoSaleEnModoAnticipo() {
        Cotizacion cotizacion = cotizacionRepository.findAll().stream()
            .filter(c -> c.getTotalEstimado() != null && c.getTotalEstimado().signum() > 0)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("No hay cotizaciones con total para probar."));
        Usuario dueño = cotizacion.getUsuario();
        BigDecimal anticipo = cotizacion.getTotalEstimado().divide(BigDecimal.valueOf(2));
        VentaRequest request = pedido("transferencia", null);
        request.setIdCotizacion(cotizacion.getIdCotizacion());
        request.setTotal(anticipo);

        Venta venta = ventaService.crear(dueño.getEmail(), false, request);

        assertEquals(cotizacion.getIdCotizacion(), venta.getCotizacion().getIdCotizacion());
        ArgumentCaptor<EmailService.DatosConfirmacionPedido> correo = ArgumentCaptor.forClass(EmailService.DatosConfirmacionPedido.class);
        verify(emailService).enviarConfirmacionPedido(eq(dueño.getEmail()), correo.capture());
        assertTrue(correo.getValue().esAnticipoServicio());
        assertEquals(cotizacion.getNumeroCotizacion(), correo.getValue().numeroCotizacion());
        assertEquals(0, cotizacion.getTotalEstimado().subtract(anticipo).compareTo(correo.getValue().saldoPendiente()));
    }

    private VentaRequest pedido(String metodoPago, String metodoEnvio, VentaRequest.Item... items) {
        VentaRequest request = new VentaRequest();
        request.setMetodoPago(metodoPago);
        request.setMetodoEnvio(metodoEnvio);
        request.setItems(List.of(items));
        return request;
    }

    private VentaRequest.Item item(Producto p, int cantidad) {
        VentaRequest.Item item = new VentaRequest.Item();
        item.setIdProducto(p.getIdProducto());
        item.setCantidad(cantidad);
        item.setPrecioVenta(p.getPrecioVenta());
        return item;
    }

    // Lectura nativa: el trigger cambia el stock directo en la BD y la caché de Hibernate no se entera.
    private int stockReal(Integer idProducto) {
        return ((Number) em.createNativeQuery("SELECT cantidad_disponible FROM inventario WHERE id_producto = ?1")
            .setParameter(1, idProducto).getSingleResult()).intValue();
    }

    private long contarVentas() {
        return ((Number) em.createNativeQuery("SELECT COUNT(*) FROM ventas").getSingleResult()).longValue();
    }
}
