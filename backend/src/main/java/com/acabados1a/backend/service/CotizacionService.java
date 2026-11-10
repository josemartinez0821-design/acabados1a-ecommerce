package com.acabados1a.backend.service;

import com.acabados1a.backend.dto.CotizacionEstadoRequest;
import com.acabados1a.backend.dto.CotizacionRequest;
import com.acabados1a.backend.model.*;
import com.acabados1a.backend.repository.*;
import com.acabados1a.backend.util.ObservacionesUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

// Cabecera + ítems (productos/servicios) se crean juntos en varias tablas - @Transactional a
// nivel de clase, mismo motivo que ProductoService: si falla a mitad de camino no debe quedar
// una cotización huérfana sin sus ítems.
@Service
@RequiredArgsConstructor
@Transactional
public class CotizacionService {

    private final CotizacionRepository cotizacionRepository;
    private final CotizacionProductoRepository cotizacionProductoRepository;
    private final CotizacionServicioRepository cotizacionServicioRepository;
    private final UsuarioRepository usuarioRepository;
    private final ProductoRepository productoRepository;
    private final ServicioRepository servicioRepository;
    private final EmailService emailService;

    public List<Cotizacion> listarParaUsuario(String email, boolean esAdmin) {
        Usuario usuario = resolverUsuario(email);
        return esAdmin ? cotizacionRepository.findAll() : cotizacionRepository.findByUsuarioIdUsuario(usuario.getIdUsuario());
    }

    public Cotizacion crear(String email, CotizacionRequest request) {
        Usuario usuario = resolverUsuario(email);

        boolean sinProductos = request.getProductos() == null || request.getProductos().isEmpty();
        boolean sinServicios = request.getServicios() == null || request.getServicios().isEmpty();
        if (sinProductos && sinServicios) {
            throw new IllegalArgumentException("La cotización debe incluir al menos un producto o servicio.");
        }

        Cotizacion cotizacion = new Cotizacion();
        // El id_usuario sale del token, nunca del body - nadie puede crear una cotización a
        // nombre de otro usuario cambiando un campo de la petición.
        cotizacion.setUsuario(usuario);
        cotizacion.setEstado(Cotizacion.Estado.pendiente);
        cotizacion.setObservaciones(request.getObservaciones());
        cotizacion.setDepartamento(request.getDepartamento());
        cotizacion.setCiudad(request.getCiudad());
        cotizacion.setValidezDias(15);

        BigDecimal totalProductos = sinProductos ? BigDecimal.ZERO : request.getProductos().stream()
            .map(i -> i.getPrecioUnitario().multiply(BigDecimal.valueOf(i.getCantidad())))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalServicios = sinServicios ? BigDecimal.ZERO : request.getServicios().stream()
            .map(CotizacionRequest.ItemServicio::getPrecioEstimado)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        cotizacion.setTotalEstimado(totalProductos.add(totalServicios));

        // numero_cotizacion depende del id real (IDENTITY) - mismo truco de placeholder temporal +
        // segundo save() ya usado para codigo_producto/codigo_servicio.
        cotizacion.setNumeroCotizacion("TMP-" + UUID.randomUUID());
        Cotizacion guardada = cotizacionRepository.save(cotizacion);
        guardada.setNumeroCotizacion("COT-" + String.format("%03d", guardada.getIdCotizacion()));
        guardada = cotizacionRepository.save(guardada);

        if (!sinProductos) {
            for (CotizacionRequest.ItemProducto item : request.getProductos()) {
                Producto producto = productoRepository.findById(item.getIdProducto())
                    .orElseThrow(() -> new IllegalArgumentException("Uno de los productos indicados no existe."));
                // Borrado lógico: un producto eliminado no se cotiza (la clase es @Transactional,
                // así que la cabecera ya guardada se deshace junto con esta excepción).
                if (Boolean.FALSE.equals(producto.getActivo())) {
                    throw new IllegalArgumentException("El producto \"" + producto.getNombre() + "\" ya no está disponible.");
                }
                CotizacionProducto cp = new CotizacionProducto();
                cp.setCotizacion(guardada);
                cp.setProducto(producto);
                cp.setCantidad(item.getCantidad());
                cp.setPrecioUnitario(item.getPrecioUnitario());
                cotizacionProductoRepository.save(cp);
            }
        }
        if (!sinServicios) {
            for (CotizacionRequest.ItemServicio item : request.getServicios()) {
                Servicio servicio = servicioRepository.findById(item.getIdServicio())
                    .orElseThrow(() -> new IllegalArgumentException("Uno de los servicios indicados no existe."));
                if (Boolean.FALSE.equals(servicio.getActivo())) {
                    throw new IllegalArgumentException("El servicio \"" + servicio.getNombreServicio() + "\" ya no está disponible.");
                }
                CotizacionServicio cs = new CotizacionServicio();
                cs.setCotizacion(guardada);
                cs.setServicio(servicio);
                cs.setCantidad(item.getCantidad());
                cs.setPrecioEstimado(item.getPrecioEstimado());
                cotizacionServicioRepository.save(cs);
            }
        }

        return guardada;
    }

    public Cotizacion actualizarEstado(String email, boolean esAdmin, Integer id, CotizacionEstadoRequest request) {
        Usuario usuario = resolverUsuario(email);
        Cotizacion cotizacion = cotizacionRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("No existe una cotización con id " + id + "."));

        boolean esDueño = cotizacion.getUsuario().getIdUsuario().equals(usuario.getIdUsuario());
        if (!esAdmin && !esDueño) {
            throw new IllegalArgumentException("No tienes permiso para modificar esta cotización.");
        }

        Cotizacion.Estado nuevoEstado;
        try {
            nuevoEstado = Cotizacion.Estado.valueOf(request.getEstado());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("El estado indicado no es válido.");
        }

        // Reglas de quién puede poner qué estado - el admin decide aprobar/rechazar; el dueño
        // (cliente) solo puede rechazar su propia cotización o confirmar que ya pagó (convertida_venta).
        if (esAdmin) {
            if (nuevoEstado != Cotizacion.Estado.aprobada && nuevoEstado != Cotizacion.Estado.rechazada) {
                throw new IllegalArgumentException("Un administrador solo puede aprobar o rechazar una cotización.");
            }
        } else {
            if (nuevoEstado != Cotizacion.Estado.rechazada && nuevoEstado != Cotizacion.Estado.convertida_venta) {
                throw new IllegalArgumentException("Solo puedes rechazar o confirmar el pago de tu propia cotización.");
            }
        }

        cotizacion.setEstado(nuevoEstado);
        if (request.getRespuesta() != null) cotizacion.setRespuesta(request.getRespuesta());
        if (esAdmin && nuevoEstado == Cotizacion.Estado.aprobada) {
            cotizacion.setFechaAprobacion(LocalDate.now());
            if (request.getTotalEstimado() != null) cotizacion.setTotalEstimado(request.getTotalEstimado());
        }

        Cotizacion guardada = cotizacionRepository.save(cotizacion);

        // Apenas el admin aprueba o rechaza, el cliente recibe por correo el resultado. Antes solo
        // existía el recordatorio de vencimiento (5 días antes); ni la aprobación ni el rechazo
        // avisaban nada. Solo cuando lo hace el admin: si el propio cliente rechaza su cotización
        // no tiene sentido enviarle un correo. @Async dentro de EmailService: no bloquea la respuesta.
        if (esAdmin && nuevoEstado == Cotizacion.Estado.aprobada) {
            enviarCorreoAprobacion(guardada);
        } else if (esAdmin && nuevoEstado == Cotizacion.Estado.rechazada) {
            emailService.enviarCotizacionRechazada(
                guardada.getUsuario().getEmail(), guardada.getNumeroCotizacion(), guardada.getRespuesta());
        }

        return guardada;
    }

    private void enviarCorreoAprobacion(Cotizacion cotizacion) {
        List<EmailService.LineaResumen> servicios = new ArrayList<>();
        for (CotizacionServicio cs : cotizacionServicioRepository.findByCotizacionIdCotizacion(cotizacion.getIdCotizacion())) {
            Servicio s = cs.getServicio();
            String detalle = EmailService.descripcionCantidadServicio(cs.getCantidad(), s.getPrecioHora() != null, s.getPrecioDia() != null);
            servicios.add(new EmailService.LineaResumen(s.getNombreServicio(), detalle, cs.getPrecioEstimado()));
        }
        List<EmailService.LineaResumen> productos = new ArrayList<>();
        for (CotizacionProducto cp : cotizacionProductoRepository.findByCotizacionIdCotizacion(cotizacion.getIdCotizacion())) {
            BigDecimal montoLinea = cp.getPrecioUnitario().multiply(BigDecimal.valueOf(cp.getCantidad()));
            productos.add(new EmailService.LineaResumen(cp.getProducto().getNombre(), "x" + cp.getCantidad(), montoLinea));
        }

        BigDecimal total = cotizacion.getTotalEstimado() != null ? cotizacion.getTotalEstimado() : BigDecimal.ZERO;
        // Mismo cálculo que montoAnticipo() en el frontend (stores/cotizaciones.js): 50% redondeado
        // al peso, para que el correo anuncie exactamente lo que el cliente verá que debe pagar.
        BigDecimal anticipo = total.multiply(new BigDecimal("0.5")).setScale(0, RoundingMode.HALF_UP);
        int validez = cotizacion.getValidezDias() != null ? cotizacion.getValidezDias() : 15;

        emailService.enviarCotizacionAprobada(cotizacion.getUsuario().getEmail(), new EmailService.DatosCotizacionAprobada(
            cotizacion.getNumeroCotizacion(), cotizacion.getRespuesta(), servicios, productos,
            total, anticipo, validez, ObservacionesUtil.extraerFechaDeseada(cotizacion.getObservaciones())));
    }

    // Job diario: le avisa por correo al cliente cuando a una cotización aprobada le quedan
    // exactamente 5 días antes de vencer (validez_dias contados desde fecha_aprobacion). Se
    // envía una sola vez por cotización (recordatorioEnviado) - sin eso, si el job corriera dos
    // veces el mismo día (reinicio del server, etc.) el cliente recibiría el correo duplicado.
    @Scheduled(cron = "0 0 8 * * *")
    public void enviarRecordatoriosVencimiento() {
        NumberFormat formatoCOP = NumberFormat.getCurrencyInstance(new Locale("es", "CO"));
        formatoCOP.setMaximumFractionDigits(0);

        for (Cotizacion c : cotizacionRepository.findByEstadoAndRecordatorioEnviadoFalse(Cotizacion.Estado.aprobada)) {
            if (c.getFechaAprobacion() == null) continue;
            int validez = c.getValidezDias() != null ? c.getValidezDias() : 15;
            long diasRestantes = ChronoUnit.DAYS.between(LocalDate.now(), c.getFechaAprobacion().plusDays(validez));
            if (diasRestantes == 5) {
                emailService.enviarRecordatorioCotizacion(c.getUsuario().getEmail(), c.getNumeroCotizacion(), formatoCOP.format(c.getTotalEstimado()));
                c.setRecordatorioEnviado(true);
                cotizacionRepository.save(c);
            }
        }
    }

    private Usuario resolverUsuario(String email) {
        return usuarioRepository.findByEmail(email)
            .orElseThrow(() -> new IllegalArgumentException("Usuario no encontrado."));
    }
}
