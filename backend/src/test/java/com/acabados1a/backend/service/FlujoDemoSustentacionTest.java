package com.acabados1a.backend.service;

import com.acabados1a.backend.dto.*;
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
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

// Recorrido de punta a punta de los flujos que se muestran en la sustentación final (11/11/2026).
// Cada prueba hace lo mismo que haría la pantalla del cliente o del administrador, llamando a los
// mismos servicios que usan los controladores. Corre contra la BD real (necesita MariaDB), igual
// que VentaServiceCrearRegresionTest, y @Transactional revierte todo al terminar: no deja
// cotizaciones, PQRS, ventas, reseñas, productos ni promociones de prueba. EmailService va
// simulado: se comprueba que se pidió cada correo, sin mandarlo de verdad.
@SpringBootTest
@Transactional
class FlujoDemoSustentacionTest {

    @Autowired CotizacionService cotizacionService;
    @Autowired VentaService ventaService;
    @Autowired PqrsService pqrsService;
    @Autowired ResenaService resenaService;
    @Autowired ProductoService productoService;
    @Autowired MovimientoInventarioService movimientoInventarioService;
    @Autowired PromocionService promocionService;
    @Autowired UsuarioRepository usuarioRepository;
    @Autowired ProductoRepository productoRepository;
    @Autowired ServicioRepository servicioRepository;
    @Autowired InventarioRepository inventarioRepository;
    @Autowired CategoriaProductoRepository categoriaRepository;
    @Autowired PagoRepository pagoRepository;
    @Autowired ResenaRepository resenaRepository;
    @Autowired PromocionRepository promocionRepository;
    @Autowired EntityManager em;

    @MockitoBean EmailService emailService;

    private Usuario cliente;
    private Usuario admin;
    private Producto producto;
    private Servicio servicio;

    @BeforeEach
    void datosBase() {
        cliente = usuarioConRol(2);
        admin = usuarioConRol(1);
        Inventario conStock = inventarioRepository.findAll().stream()
            .filter(i -> i.getCantidadDisponible() != null && i.getCantidadDisponible() >= 5)
            .filter(i -> productoRepository.findById(i.getIdProducto()).map(p -> !Boolean.FALSE.equals(p.getActivo())).orElse(false))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("No hay un producto activo con stock para probar."));
        producto = productoRepository.findById(conStock.getIdProducto()).orElseThrow();
        servicio = servicioRepository.findAll().stream()
            .filter(s -> !Boolean.FALSE.equals(s.getActivo()))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("No hay servicios activos para probar."));
    }

    // RF de cotizaciones: el cliente solicita → el admin aprueba → el cliente paga el anticipo
    // (50 %) → la cotización queda convertida en venta.
    @Test
    void cotizacion_clienteSolicita_adminAprueba_clientePagaAnticipo() {
        int stockAntes = stockReal(producto.getIdProducto());
        BigDecimal precioServicio = new BigDecimal("150000");

        Cotizacion cotizacion = cotizacionService.crear(cliente.getEmail(),
            solicitud(itemProducto(producto, 2), itemServicio(servicio, precioServicio)));

        assertEquals(Cotizacion.Estado.pendiente, cotizacion.getEstado());
        assertEquals(String.format("COT-%03d", cotizacion.getIdCotizacion()), cotizacion.getNumeroCotizacion());
        BigDecimal totalSolicitado = producto.getPrecioVenta().multiply(BigDecimal.valueOf(2)).add(precioServicio);
        assertEquals(0, totalSolicitado.compareTo(cotizacion.getTotalEstimado()));
        assertEquals(15, cotizacion.getValidezDias());

        // El admin aprueba con un total ajustado y una respuesta para el cliente.
        BigDecimal totalAprobado = new BigDecimal("400000");
        CotizacionEstadoRequest aprobar = new CotizacionEstadoRequest();
        aprobar.setEstado("aprobada");
        aprobar.setRespuesta("Aprobada. Incluye visita técnica.");
        aprobar.setTotalEstimado(totalAprobado);
        Cotizacion aprobada = cotizacionService.actualizarEstado(admin.getEmail(), true, cotizacion.getIdCotizacion(), aprobar);

        assertEquals(Cotizacion.Estado.aprobada, aprobada.getEstado());
        assertEquals(LocalDate.now(), aprobada.getFechaAprobacion());
        assertEquals(0, totalAprobado.compareTo(aprobada.getTotalEstimado()));
        ArgumentCaptor<EmailService.DatosCotizacionAprobada> correoAprobacion = ArgumentCaptor.forClass(EmailService.DatosCotizacionAprobada.class);
        verify(emailService).enviarCotizacionAprobada(eq(cliente.getEmail()), correoAprobacion.capture());
        assertEquals(0, new BigDecimal("200000").compareTo(correoAprobacion.getValue().anticipo()));

        // El cliente paga el anticipo: venta sin ítems ligada a la cotización (CotizacionesView.vue).
        VentaRequest anticipo = new VentaRequest();
        anticipo.setItems(List.of());
        anticipo.setSubtotal(new BigDecimal("200000"));
        anticipo.setTotal(new BigDecimal("200000"));
        anticipo.setMetodoPago("nequi");
        anticipo.setMetodoEnvio("recogida");
        anticipo.setIdCotizacion(cotizacion.getIdCotizacion());
        Venta venta = ventaService.crear(cliente.getEmail(), false, anticipo);

        assertEquals(cotizacion.getIdCotizacion(), venta.getCotizacion().getIdCotizacion());
        assertEquals(Pago.Estado.completado, pagoRepository.findByVentaIdVenta(venta.getIdVenta()).get(0).getEstado());
        assertEquals(stockAntes, stockReal(producto.getIdProducto()), "el anticipo no mueve el inventario");
        ArgumentCaptor<EmailService.DatosConfirmacionPedido> correoAnticipo = ArgumentCaptor.forClass(EmailService.DatosConfirmacionPedido.class);
        verify(emailService).enviarConfirmacionPedido(eq(cliente.getEmail()), correoAnticipo.capture());
        assertTrue(correoAnticipo.getValue().esAnticipoServicio());

        CotizacionEstadoRequest convertir = new CotizacionEstadoRequest();
        convertir.setEstado("convertida_venta");
        Cotizacion convertida = cotizacionService.actualizarEstado(cliente.getEmail(), false, cotizacion.getIdCotizacion(), convertir);
        assertEquals(Cotizacion.Estado.convertida_venta, convertida.getEstado());
    }

    @Test
    void cotizacion_adminRechaza_yElClienteRecibeElMotivo() {
        Cotizacion cotizacion = cotizacionService.crear(cliente.getEmail(), solicitud(itemProducto(producto, 1)));

        CotizacionEstadoRequest rechazar = new CotizacionEstadoRequest();
        rechazar.setEstado("rechazada");
        rechazar.setRespuesta("No hay disponibilidad en la fecha pedida.");
        Cotizacion rechazada = cotizacionService.actualizarEstado(admin.getEmail(), true, cotizacion.getIdCotizacion(), rechazar);

        assertEquals(Cotizacion.Estado.rechazada, rechazada.getEstado());
        verify(emailService).enviarCotizacionRechazada(cliente.getEmail(), cotizacion.getNumeroCotizacion(), "No hay disponibilidad en la fecha pedida.");
    }

    // H1 de la revisión del 10/11: un servicio eliminado (activo = 0) ya no se puede cotizar.
    @Test
    void cotizacion_conServicioEliminado_seRechaza() {
        servicio.setActivo(false);
        servicioRepository.saveAndFlush(servicio);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> cotizacionService.crear(cliente.getEmail(), solicitud(itemServicio(servicio, new BigDecimal("90000")))));
        assertEquals("El servicio \"" + servicio.getNombreServicio() + "\" ya no está disponible.", e.getMessage());
    }

    // Compra con envío → el admin la marca despachada con la guía (el cliente recibe el correo
    // con la guía y la transportadora) → luego registra la devolución (el stock vuelve).
    @Test
    void venta_conEnvio_despachoConGuia_yDevolucionReponeElStock() {
        int stockAntes = stockReal(producto.getIdProducto());
        VentaRequest pedido = new VentaRequest();
        pedido.setMetodoPago("transferencia");
        pedido.setMetodoEnvio("envio");
        pedido.setItems(List.of(item(producto, 2)));
        Venta venta = ventaService.crear(cliente.getEmail(), false, pedido);
        assertEquals(stockAntes - 2, stockReal(producto.getIdProducto()));
        // En la app cada petición HTTP trae su propio contexto de persistencia; aquí todo corre en
        // la misma transacción, así que se limpia la caché para no reponer sobre el stock viejo
        // (el que había antes de que el trigger lo descontara).
        em.clear();

        VentaEstadoRequest despachar = new VentaEstadoRequest();
        despachar.setEstado("entregado");
        despachar.setNumeroGuia("GUIA-PRUEBA-001");
        despachar.setTransportadora("Servientrega");
        Venta despachada = ventaService.actualizarEstado(admin.getEmail(), venta.getIdVenta(), despachar);

        assertEquals(Venta.Estado.entregado, despachada.getEstado());
        assertEquals("GUIA-PRUEBA-001", despachada.getNumeroGuia());
        assertEquals("Servientrega", despachada.getTransportadora());
        verify(emailService).enviarNotificacionDespacho(cliente.getEmail(), venta.getNumeroVenta(), "GUIA-PRUEBA-001", "Servientrega");

        VentaEstadoRequest devolver = new VentaEstadoRequest();
        devolver.setEstado("devuelto");
        devolver.setMotivo("Producto con la tapa golpeada");
        Venta devuelta = ventaService.actualizarEstado(admin.getEmail(), venta.getIdVenta(), devolver);

        em.flush();
        assertEquals(Venta.Estado.devuelto, devuelta.getEstado());
        assertEquals(stockAntes, stockReal(producto.getIdProducto()), "la devolución repone el stock");
        assertEquals(Pago.Estado.reversado, pagoRepository.findByVentaIdVenta(venta.getIdVenta()).get(0).getEstado());
        assertTrue(devuelta.getNotasInternas().contains("Producto con la tapa golpeada"));
        verify(emailService).enviarNotificacionCancelacion(cliente.getEmail(), venta.getNumeroVenta(), true, true);
    }

    // Recogida en tienda: no pasa por transportadora, así que la guía se ignora aunque llegue.
    @Test
    void venta_deRecogida_noGuardaGuia() {
        VentaRequest pedido = new VentaRequest();
        pedido.setMetodoPago("efectivo");
        pedido.setMetodoEnvio("recogida");
        pedido.setItems(List.of(item(producto, 1)));
        Venta venta = ventaService.crear(cliente.getEmail(), false, pedido);

        VentaEstadoRequest entregar = new VentaEstadoRequest();
        entregar.setEstado("entregado");
        entregar.setNumeroGuia("NO-APLICA");
        Venta entregada = ventaService.actualizarEstado(admin.getEmail(), venta.getIdVenta(), entregar);

        assertNull(entregada.getNumeroGuia());
        assertNotNull(entregada.getFechaEntregaReal());
    }

    @Test
    void pqrs_clienteRadica_adminResponde() {
        PqrsRequest solicitud = new PqrsRequest();
        solicitud.setTipo("queja");
        solicitud.setAsunto("Demora en la entrega");
        solicitud.setDescripcion("El pedido llegó dos días después de lo acordado.");
        solicitud.setDepartamento("Huila");
        solicitud.setCiudad("Tesalia");
        Pqrs pqrs = pqrsService.crear(cliente.getEmail(), solicitud);

        assertEquals(Pqrs.Estado.abierto, pqrs.getEstado());
        assertEquals(Pqrs.Prioridad.media, pqrs.getPrioridad());
        assertEquals("PQRS-" + LocalDate.now().getYear() + "-" + String.format("%03d", pqrs.getIdPqrs()), pqrs.getNumeroPqrs());

        PqrsEstadoRequest respuesta = new PqrsEstadoRequest();
        respuesta.setEstado("resuelto");
        respuesta.setPrioridad("alta");
        respuesta.setRespuesta("Lamentamos la demora; le aplicamos envío gratis en su próxima compra.");
        Pqrs resuelta = pqrsService.actualizar(admin.getEmail(), pqrs.getIdPqrs(), respuesta);

        assertEquals(Pqrs.Estado.resuelto, resuelta.getEstado());
        assertEquals(Pqrs.Prioridad.alta, resuelta.getPrioridad());
        assertNotNull(resuelta.getFechaResolucion());
        assertEquals(admin.getIdUsuario(), resuelta.getResponsable().getIdUsuario());
    }

    @Test
    void resena_clienteCalifica_yNoPuedeRepetir() {
        Producto sinResena = productoRepository.findAll().stream()
            .filter(p -> !Boolean.FALSE.equals(p.getActivo()))
            .filter(p -> !resenaRepository.existsByProductoIdProductoAndUsuarioIdUsuario(p.getIdProducto(), cliente.getIdUsuario()))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("El cliente ya reseñó todos los productos."));
        ResenaRequest request = new ResenaRequest();
        request.setIdProducto(sinResena.getIdProducto());
        request.setCalificacion(5);
        request.setComentario("Muy buen cubrimiento.");

        Resena resena = resenaService.crear(cliente.getEmail(), request);
        assertNotNull(resena.getIdResena());
        assertTrue(resenaService.listar().stream().anyMatch(r -> r.getIdResena().equals(resena.getIdResena())));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> resenaService.crear(cliente.getEmail(), request));
        assertEquals("Ya escribiste una reseña para este producto.", e.getMessage());
    }

    // Admin: crea un producto con stock inicial → lo edita → registra una entrada de inventario →
    // lo elimina (borrado lógico) y desde ese momento ya no se puede vender (H1).
    @Test
    void producto_adminCreaEditaAjustaStockYElimina() {
        Integer idCategoria = categoriaRepository.findAll().get(0).getIdCategoria();
        ProductoRequest nuevo = new ProductoRequest();
        nuevo.setNombre("Producto de prueba del flujo");
        nuevo.setPrecioVenta(new BigDecimal("25000"));
        nuevo.setIdCategoria(idCategoria);
        nuevo.setStockInicial(10);
        Producto creado = productoService.crear(nuevo);

        assertEquals(String.format("PROD-%03d", creado.getIdProducto()), creado.getCodigoProducto());
        assertTrue(creado.getActivo());
        assertEquals(10, inventarioRepository.findByIdProducto(creado.getIdProducto()).orElseThrow().getCantidadDisponible());

        nuevo.setPrecioVenta(new BigDecimal("27500"));
        nuevo.setStockInicial(null);
        Producto editado = productoService.actualizar(creado.getIdProducto(), nuevo);
        assertEquals(0, new BigDecimal("27500").compareTo(editado.getPrecioVenta()));

        MovimientoInventarioRequest entrada = new MovimientoInventarioRequest();
        entrada.setIdProducto(creado.getIdProducto());
        entrada.setTipoMovimiento("entrada");
        entrada.setCantidad(5);
        entrada.setDescripcion("Llegada de proveedor");
        movimientoInventarioService.registrar(admin.getEmail(), entrada);
        assertEquals(15, inventarioRepository.findByIdProducto(creado.getIdProducto()).orElseThrow().getCantidadDisponible());

        productoService.eliminar(creado.getIdProducto());
        assertFalse(productoRepository.findById(creado.getIdProducto()).orElseThrow().getActivo());

        VentaRequest pedido = new VentaRequest();
        pedido.setMetodoPago("efectivo");
        pedido.setMetodoEnvio("recogida");
        pedido.setItems(List.of(item(editado, 1)));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> ventaService.crear(cliente.getEmail(), false, pedido));
        assertEquals("El producto \"Producto de prueba del flujo\" ya no está disponible.", e.getMessage());
        verify(emailService, never()).enviarConfirmacionPedido(anyString(), any());
    }

    @Test
    void promocion_adminCreaEditaYElimina() {
        PromocionRequest request = new PromocionRequest();
        request.setTitulo("Promo de prueba del flujo");
        request.setTipo("descuento");
        request.setDescuentoPorcentaje(new BigDecimal("10"));
        request.setProductos(List.of(producto.getIdProducto()));
        request.setFechaInicio(LocalDate.now());
        request.setFechaFin(LocalDate.now().plusDays(7));
        Promocion creada = promocionService.crear(request);
        assertEquals(Promocion.Tipo.descuento, creada.getTipo());
        assertEquals(1, creada.getProductos().size());

        request.setDescuentoPorcentaje(new BigDecimal("15"));
        Promocion editada = promocionService.actualizar(creada.getIdPromocion(), request);
        assertEquals(0, new BigDecimal("15").compareTo(editada.getDescuentoPorcentaje()));

        request.setFechaFin(LocalDate.now().minusDays(1));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> promocionService.actualizar(creada.getIdPromocion(), request));
        assertEquals("La fecha de fin no puede ser anterior a la fecha de inicio.", e.getMessage());

        promocionService.eliminar(creada.getIdPromocion());
        assertFalse(promocionRepository.existsById(creada.getIdPromocion()));
    }

    private Usuario usuarioConRol(int idRol) {
        return usuarioRepository.findAll().stream()
            .filter(u -> u.getRol() != null && u.getRol().getIdRol() == idRol)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("No hay usuarios con rol " + idRol + " para probar."));
    }

    private CotizacionRequest solicitud(Object... items) {
        CotizacionRequest request = new CotizacionRequest();
        request.setObservaciones("Prueba del flujo de la sustentación");
        request.setDepartamento("Huila");
        request.setCiudad("Tesalia");
        request.setProductos(List.of(items).stream()
            .filter(CotizacionRequest.ItemProducto.class::isInstance).map(CotizacionRequest.ItemProducto.class::cast).toList());
        request.setServicios(List.of(items).stream()
            .filter(CotizacionRequest.ItemServicio.class::isInstance).map(CotizacionRequest.ItemServicio.class::cast).toList());
        return request;
    }

    private CotizacionRequest.ItemProducto itemProducto(Producto p, int cantidad) {
        CotizacionRequest.ItemProducto item = new CotizacionRequest.ItemProducto();
        item.setIdProducto(p.getIdProducto());
        item.setCantidad(cantidad);
        item.setPrecioUnitario(p.getPrecioVenta());
        return item;
    }

    private CotizacionRequest.ItemServicio itemServicio(Servicio s, BigDecimal precio) {
        CotizacionRequest.ItemServicio item = new CotizacionRequest.ItemServicio();
        item.setIdServicio(s.getIdServicio());
        item.setCantidad(BigDecimal.ONE);
        item.setPrecioEstimado(precio);
        return item;
    }

    private VentaRequest.Item item(Producto p, int cantidad) {
        VentaRequest.Item item = new VentaRequest.Item();
        item.setIdProducto(p.getIdProducto());
        item.setCantidad(cantidad);
        item.setPrecioVenta(p.getPrecioVenta());
        return item;
    }

    // Lectura nativa: los triggers cambian el stock directo en la BD y la caché de Hibernate no se entera.
    private int stockReal(Integer idProducto) {
        em.flush();
        return ((Number) em.createNativeQuery("SELECT cantidad_disponible FROM inventario WHERE id_producto = ?1")
            .setParameter(1, idProducto).getSingleResult()).intValue();
    }
}
