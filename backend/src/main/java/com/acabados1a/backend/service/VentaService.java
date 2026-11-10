package com.acabados1a.backend.service;

import com.acabados1a.backend.dto.VentaEstadoRequest;
import com.acabados1a.backend.dto.VentaNotasRequest;
import com.acabados1a.backend.dto.VentaRequest;
import com.acabados1a.backend.model.*;
import com.acabados1a.backend.repository.*;
import com.acabados1a.backend.util.ObservacionesUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// Cabecera + detalle + pago se crean juntos, y de paso hay que tocar Inventario (descuenta stock
// y registra el movimiento) - todo en una sola transacción, mismo motivo que CotizacionService: si
// algo falla a mitad de camino no debe quedar una venta a medias con el stock ya descontado.
@Service
@RequiredArgsConstructor
@Transactional
public class VentaService {

    private final VentaRepository ventaRepository;
    private final DetalleVentaRepository detalleVentaRepository;
    private final PagoRepository pagoRepository;
    private final MovimientoInventarioRepository movimientoInventarioRepository;
    private final InventarioRepository inventarioRepository;
    private final UsuarioRepository usuarioRepository;
    private final ProductoRepository productoRepository;
    private final CotizacionRepository cotizacionRepository;
    private final CotizacionServicioRepository cotizacionServicioRepository;
    private final EmailService emailService;

    public List<Venta> listarParaUsuario(String email, boolean esAdmin) {
        Usuario actor = resolverUsuario(email);
        return esAdmin ? ventaRepository.findAll() : ventaRepository.findByUsuarioIdUsuario(actor.getIdUsuario());
    }

    // Coordina el alta de una venta; cada paso vive en su propio método (hallazgo NC-01 de la
    // auditoría cruzada: antes todo esto era un solo método de ~165 líneas). El orden importa:
    // primero TODAS las validaciones (dueño, métodos de pago/envío, cotización y stock) y recién
    // después las escrituras, para que un pedido inválido no alcance a insertar nada.
    public Venta crear(String email, boolean esAdmin, VentaRequest request) {
        Usuario actor = resolverUsuario(email);
        Usuario dueño = resolverDueñoVenta(actor, esAdmin, request.getIdUsuario());
        Venta.MetodoPago metodoPago = validarMetodoPago(request.getMetodoPago());
        Venta.MetodoEnvio metodoEnvio = resolverMetodoEnvio(request.getMetodoEnvio());

        // Contraentrega es para envío: el cliente paga al recoger el paquete en el local de la
        // transportadora, no en nuestra tienda (recogida en tienda ya es gratis y sin este paso).
        if (metodoPago == Venta.MetodoPago.contraentrega && metodoEnvio != Venta.MetodoEnvio.envio) {
            throw new IllegalArgumentException("Contraentrega solo está disponible para envío.");
        }

        List<VentaRequest.Item> items = request.getItems() != null ? request.getItems() : List.of();
        BigDecimal subtotal = request.getSubtotal() != null ? request.getSubtotal() : calcularSubtotal(items);
        BigDecimal total = request.getTotal() != null ? request.getTotal() : subtotal;
        Cotizacion cotizacion = resolverCotizacion(request.getIdCotizacion(), actor, esAdmin);
        Map<Integer, Producto> productosPorId = validarStock(items);

        Venta guardada = guardarCabecera(dueño, metodoPago, metodoEnvio, subtotal, total, request.getNotasCliente(), cotizacion);
        crearDetallesVenta(guardada, items, productosPorId);
        crearPago(guardada);
        enviarConfirmacion(dueño, guardada, items, productosPorId);
        return guardada;
    }

    // Solo un admin puede registrar la venta a nombre de otro cliente (pedido manual por
    // teléfono/WhatsApp) - cualquier otro usuario siempre compra a nombre de sí mismo, sin
    // importar qué id_usuario venga en el body.
    private Usuario resolverDueñoVenta(Usuario actor, boolean esAdmin, Integer idUsuarioSolicitado) {
        if (!esAdmin || idUsuarioSolicitado == null) return actor;
        return usuarioRepository.findById(idUsuarioSolicitado)
            .orElseThrow(() -> new IllegalArgumentException("El cliente indicado no existe."));
    }

    private Venta.MetodoPago validarMetodoPago(String metodoPago) {
        try {
            return Venta.MetodoPago.valueOf(metodoPago);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("El método de pago indicado no es válido.");
        }
    }

    // metodoEnvio es opcional: el anticipo de una cotización de servicio no envía nada y el
    // frontend no lo manda. Si no viene, se asume 'recogida' (el valor del enum que representa
    // "sin envío"). El null se filtra aparte porque MetodoEnvio.valueOf(null) lanzaría
    // NullPointerException, no IllegalArgumentException, y se escaparía del catch.
    private Venta.MetodoEnvio resolverMetodoEnvio(String metodoEnvio) {
        if (metodoEnvio == null || metodoEnvio.isBlank()) return Venta.MetodoEnvio.recogida;
        try {
            return Venta.MetodoEnvio.valueOf(metodoEnvio);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("El método de envío indicado no es válido.");
        }
    }

    private BigDecimal calcularSubtotal(List<VentaRequest.Item> items) {
        return items.stream()
            .map(i -> i.getPrecioVenta().multiply(BigDecimal.valueOf(i.getCantidad())))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    // Un cliente no puede asociar su venta a una cotización ajena (solo la suya) - el admin sí
    // puede, para registrar el anticipo de cualquier cliente a mano.
    private Cotizacion resolverCotizacion(Integer idCotizacion, Usuario actor, boolean esAdmin) {
        if (idCotizacion == null) return null;
        Cotizacion cotizacion = cotizacionRepository.findById(idCotizacion)
            .orElseThrow(() -> new IllegalArgumentException("La cotización indicada no existe."));
        if (!esAdmin && !cotizacion.getUsuario().getIdUsuario().equals(actor.getIdUsuario())) {
            throw new IllegalArgumentException("No tienes permiso para asociar esa cotización a la venta.");
        }
        return cotizacion;
    }

    // Valida stock ANTES de insertar nada - acumulando por producto, porque el mismo producto
    // podría venir repartido en más de un ítem (un cliente API arbitrario podría mandar eso
    // aunque la UI ya los fusiona en uno solo antes de enviar). Antes de NC-01 esto corría después
    // de insertar la cabecera: el rollback la deshacía, pero el INSERT (y su id) ya se había gastado.
    // Devuelve los productos ya cargados para no volver a buscarlos en el detalle y el correo.
    private Map<Integer, Producto> validarStock(List<VentaRequest.Item> items) {
        Map<Integer, Integer> cantidadPorProducto = new LinkedHashMap<>();
        for (VentaRequest.Item item : items) {
            cantidadPorProducto.merge(item.getIdProducto(), item.getCantidad(), Integer::sum);
        }
        Map<Integer, Producto> productosPorId = new LinkedHashMap<>();
        for (Map.Entry<Integer, Integer> entry : cantidadPorProducto.entrySet()) {
            Producto producto = productoRepository.findById(entry.getKey())
                .orElseThrow(() -> new IllegalArgumentException("Uno de los productos indicados no existe."));
            // Eliminar un producto es un borrado lógico (activo = false): ya no sale en el catálogo,
            // pero sin esta validación se podía comprar entrando por su enlace directo.
            if (Boolean.FALSE.equals(producto.getActivo())) {
                throw new IllegalArgumentException("El producto \"" + producto.getNombre() + "\" ya no está disponible.");
            }
            Inventario inventario = inventarioRepository.findByIdProducto(producto.getIdProducto())
                .orElseThrow(() -> new IllegalArgumentException("El producto \"" + producto.getNombre() + "\" no tiene inventario registrado."));
            if (inventario.getCantidadDisponible() < entry.getValue()) {
                throw new IllegalArgumentException("No hay suficiente stock de \"" + producto.getNombre()
                    + "\" (disponible: " + inventario.getCantidadDisponible() + ", solicitado: " + entry.getValue() + ").");
            }
            productosPorId.put(entry.getKey(), producto);
        }
        return productosPorId;
    }

    // El numero_venta NO se genera en Java: el placeholder es solo para pasar la validación
    // not-null de Hibernate antes del INSERT, y el trigger before_venta_insert lo reemplaza por el
    // real durante ese mismo INSERT. A diferencia de codigo_producto/numero_cotizacion (que sí
    // necesitan un segundo save() en Java porque no tienen trigger), acá NO se vuelve a escribir:
    // un segundo save() pisaría lo que el trigger ya generó con un valor calculado en Java, dejando
    // el trigger sin ningún efecto real (hallazgo H-3). En vez de eso, se lee la columna real con
    // una consulta nativa, porque el first-level cache de Hibernate no se entera de lo que cambió
    // el trigger.
    private Venta guardarCabecera(Usuario dueño, Venta.MetodoPago metodoPago, Venta.MetodoEnvio metodoEnvio,
                                  BigDecimal subtotal, BigDecimal total, String notasCliente, Cotizacion cotizacion) {
        Venta venta = new Venta();
        venta.setUsuario(dueño);
        venta.setSubtotal(subtotal);
        venta.setDescuento(BigDecimal.ZERO);
        venta.setIvaTotal(BigDecimal.ZERO);
        venta.setTotal(total);
        venta.setEstado(Venta.Estado.pendiente);
        venta.setMetodoPago(metodoPago);
        venta.setMetodoEnvio(metodoEnvio);
        venta.setNotasCliente(notasCliente);
        venta.setCotizacion(cotizacion);
        venta.setNumeroVenta("TMP-" + UUID.randomUUID());

        Venta guardada = ventaRepository.save(venta);
        guardada.setNumeroVenta(ventaRepository.obtenerNumeroVentaReal(guardada.getIdVenta()));
        return guardada;
    }

    // El descuento de stock + el registro en movimientos_inventario NO se hacen aquí en Java -
    // ya existe un trigger real en la BD (`after_venta_detalle_insert`, ver el .sql de schema)
    // que se dispara solo con este INSERT y hace exactamente eso. Duplicarlo acá restaba el
    // stock dos veces (confirmado probando contra la BD real). Cancelar/devolver sí necesita
    // lógica en Java (reponerStock más abajo) porque no existe un trigger simétrico para eso.
    private void crearDetallesVenta(Venta venta, List<VentaRequest.Item> items, Map<Integer, Producto> productosPorId) {
        for (VentaRequest.Item item : items) {
            DetalleVenta detalle = new DetalleVenta();
            detalle.setVenta(venta);
            detalle.setProducto(productosPorId.get(item.getIdProducto()));
            detalle.setCantidad(item.getCantidad());
            detalle.setPrecioUnitario(item.getPrecioVenta());
            detalleVentaRepository.save(detalle);
        }
    }

    // Los métodos que sí se cobran "en línea" (simulados) quedan completado de una vez, como
    // siempre. Contraentrega no se cobra hasta que el cliente recoge el pedido y paga en
    // persona - queda pendiente hasta que VentaService.actualizarEstado() lo complete solo.
    private void crearPago(Venta venta) {
        boolean contraentrega = venta.getMetodoPago() == Venta.MetodoPago.contraentrega;
        Pago pago = new Pago();
        pago.setVenta(venta);
        pago.setMetodoPago(venta.getMetodoPago());
        pago.setValor(venta.getTotal());
        pago.setEstado(contraentrega ? Pago.Estado.pendiente : Pago.Estado.completado);
        pago.setTransaccionId(contraentrega ? null : "SIM-" + String.format("%06d", venta.getIdVenta()));
        pagoRepository.save(pago);
    }

    // Resumen para el correo de confirmación. Los productos salen de este pedido; los servicios,
    // de la cotización asociada (si la hay). El anticipo del 50% de una cotización de solo
    // servicios llega con items vacío -> el correo se arma en "modo anticipo" (encabezado y
    // desglose propios) en vez de mostrar "Productos:" sin nada debajo como antes.
    private void enviarConfirmacion(Usuario dueño, Venta venta, List<VentaRequest.Item> items, Map<Integer, Producto> productosPorId) {
        Cotizacion cot = venta.getCotizacion();
        boolean esAnticipoServicio = cot != null && items.isEmpty();
        BigDecimal saldoPendiente = esAnticipoServicio && cot.getTotalEstimado() != null
            ? cot.getTotalEstimado().subtract(venta.getTotal()).max(BigDecimal.ZERO)
            : null;

        emailService.enviarConfirmacionPedido(dueño.getEmail(), new EmailService.DatosConfirmacionPedido(
            venta.getNumeroVenta(), lineasProducto(items, productosPorId), lineasServicio(cot), venta.getTotal(),
            venta.getMetodoPago() == Venta.MetodoPago.contraentrega, esAnticipoServicio,
            cot != null ? cot.getNumeroCotizacion() : null, saldoPendiente,
            cot != null ? ObservacionesUtil.extraerFechaDeseada(cot.getObservaciones()) : null));
    }

    private List<EmailService.LineaResumen> lineasProducto(List<VentaRequest.Item> items, Map<Integer, Producto> productosPorId) {
        List<EmailService.LineaResumen> lineas = new ArrayList<>();
        for (VentaRequest.Item item : items) {
            Producto producto = productosPorId.get(item.getIdProducto());
            BigDecimal montoLinea = item.getPrecioVenta().multiply(BigDecimal.valueOf(item.getCantidad()));
            lineas.add(new EmailService.LineaResumen(producto.getNombre(), "x" + item.getCantidad(), montoLinea));
        }
        return lineas;
    }

    private List<EmailService.LineaResumen> lineasServicio(Cotizacion cotizacion) {
        List<EmailService.LineaResumen> lineas = new ArrayList<>();
        if (cotizacion == null) return lineas;
        for (CotizacionServicio cs : cotizacionServicioRepository.findByCotizacionIdCotizacion(cotizacion.getIdCotizacion())) {
            Servicio s = cs.getServicio();
            String detalle = EmailService.descripcionCantidadServicio(cs.getCantidad(), s.getPrecioHora() != null, s.getPrecioDia() != null);
            lineas.add(new EmailService.LineaResumen(s.getNombreServicio(), detalle, cs.getPrecioEstimado()));
        }
        return lineas;
    }

    // Solo lo llama un admin (forzado en SecurityConfig) - marcar despachado/entregado, o
    // cancelar/devolver, que es lo único que de verdad repone stock y reversa el pago.
    public Venta actualizarEstado(String email, Integer id, VentaEstadoRequest request) {
        Usuario actor = resolverUsuario(email);
        Venta venta = ventaRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("No existe una venta con id " + id + "."));

        Venta.Estado nuevoEstado;
        try {
            nuevoEstado = Venta.Estado.valueOf(request.getEstado());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("El estado indicado no es válido.");
        }

        Venta.Estado estadoAnterior = venta.getEstado();
        venta.setEstado(nuevoEstado);

        boolean recienEntregado = nuevoEstado == Venta.Estado.entregado && estadoAnterior != Venta.Estado.entregado;
        if (recienEntregado) {
            if (venta.getFechaEntregaReal() == null) venta.setFechaEntregaReal(LocalDate.now());

            // La guía solo aplica a envío (un pedido de recogida no pasa por transportadora) - si
            // el admin la manda igual para un pedido de recogida, se ignora a propósito.
            if (venta.getMetodoEnvio() == Venta.MetodoEnvio.envio) {
                if (request.getNumeroGuia() != null && !request.getNumeroGuia().isBlank()) venta.setNumeroGuia(request.getNumeroGuia());
                if (request.getTransportadora() != null && !request.getTransportadora().isBlank()) venta.setTransportadora(request.getTransportadora());
            }

            // Contraentrega (siempre de envío): el cliente paga al recoger en el local de la
            // transportadora, y el negocio no se conecta con ninguna API para saber el momento
            // exacto en que eso pasa - a propósito, se simplifica marcando el pago como recibido
            // en el mismo clic de "despachado", sin un paso aparte para confirmarlo después.
            for (Pago pago : pagoRepository.findByVentaIdVenta(id)) {
                if (pago.getMetodoPago() == Venta.MetodoPago.contraentrega && pago.getEstado() == Pago.Estado.pendiente) {
                    pago.setEstado(Pago.Estado.completado);
                    pagoRepository.save(pago);
                }
            }

            emailService.enviarNotificacionDespacho(venta.getUsuario().getEmail(), venta.getNumeroVenta(), venta.getNumeroGuia(), venta.getTransportadora());
        }

        boolean esCancelacion = nuevoEstado == Venta.Estado.cancelado || nuevoEstado == Venta.Estado.devuelto;
        boolean yaEstabaCancelada = estadoAnterior == Venta.Estado.cancelado || estadoAnterior == Venta.Estado.devuelto;
        if (esCancelacion && !yaEstabaCancelada) {
            String accion = nuevoEstado == Venta.Estado.cancelado ? "Cancelación" : "Devolución";
            String motivoCliente = request.getMotivo() != null && !request.getMotivo().isBlank() ? request.getMotivo().trim() : null;
            String descripcionMovimiento = accion + " del pedido " + venta.getNumeroVenta() + (motivoCliente != null ? ": " + motivoCliente : "");
            for (DetalleVenta detalle : detalleVentaRepository.findByVentaIdVenta(id)) {
                reponerStock(detalle.getProducto(), detalle.getCantidad(), actor, descripcionMovimiento);
            }
            // Queda también en las notas internas (no solo en el historial de movimientos) para que
            // se vea de una vez al abrir el pedido en el panel, sin tener que ir a buscarlo en Inventario.
            if (motivoCliente != null) {
                String notaMotivo = "Motivo de la " + accion.toLowerCase() + ": " + motivoCliente;
                venta.setNotasInternas(venta.getNotasInternas() == null || venta.getNotasInternas().isBlank()
                    ? notaMotivo : venta.getNotasInternas() + "\n" + notaMotivo);
            }
            boolean reembolsoPendiente = false;
            for (Pago pago : pagoRepository.findByVentaIdVenta(id)) {
                if (pago.getEstado() != Pago.Estado.pendiente) {
                    reembolsoPendiente = true;
                    pago.setEstado(Pago.Estado.reversado);
                    pagoRepository.save(pago);
                }
            }
            emailService.enviarNotificacionCancelacion(venta.getUsuario().getEmail(), venta.getNumeroVenta(),
                nuevoEstado == Venta.Estado.devuelto, reembolsoPendiente);
        }

        return ventaRepository.save(venta);
    }

    public Venta actualizarNotas(Integer id, VentaNotasRequest request) {
        Venta venta = ventaRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("No existe una venta con id " + id + "."));
        if (request.getNotasInternas() != null) venta.setNotasInternas(request.getNotasInternas());
        if (request.getFechaEntregaEstimada() != null) {
            try {
                venta.setFechaEntregaEstimada(LocalDate.parse(request.getFechaEntregaEstimada()));
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException("La fecha de entrega estimada no tiene un formato válido.");
            }
        }
        return ventaRepository.save(venta);
    }

    private void reponerStock(Producto producto, int cantidad, Usuario actor, String descripcion) {
        Inventario inventario = inventarioRepository.findByIdProducto(producto.getIdProducto()).orElse(null);
        if (inventario == null) return;
        inventario.setCantidadDisponible(inventario.getCantidadDisponible() + cantidad);
        inventario.setFechaUltimaEntrada(LocalDateTime.now());
        inventarioRepository.save(inventario);

        registrarMovimiento(producto, MovimientoInventario.TipoMovimiento.devolucion, cantidad, actor, descripcion);
    }

    private void registrarMovimiento(Producto producto, MovimientoInventario.TipoMovimiento tipo, int cantidad, Usuario actor, String descripcion) {
        MovimientoInventario movimiento = new MovimientoInventario();
        movimiento.setProducto(producto);
        movimiento.setTipoMovimiento(tipo);
        movimiento.setCantidad(Math.abs(cantidad));
        movimiento.setUsuario(actor);
        movimiento.setDescripcion(descripcion);
        movimientoInventarioRepository.save(movimiento);
    }

    private Usuario resolverUsuario(String email) {
        return usuarioRepository.findByEmail(email)
            .orElseThrow(() -> new IllegalArgumentException("Usuario no encontrado."));
    }
}
