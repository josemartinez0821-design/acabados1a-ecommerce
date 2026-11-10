<script setup>
// RF10-RF12 - checkout en 3 pasos: dirección, pago, confirmación
import { ref, reactive, computed, watch, onMounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useCartStore } from '../stores/cart'
import { useCotizacionesStore } from '../stores/cotizaciones'
import { useVentasStore } from '../stores/ventas'
import { useAuthStore } from '../stores/auth'
import { useCatalogStore } from '../stores/catalog'
import { useToast } from '../composables/useToast'
import { formatCOP, empacarDireccionEntrega } from '../composables/useFormat'
import { DEPARTAMENTOS, MUNICIPIOS_POR_DEPARTAMENTO } from '../data/colombia'
import logoUrl from '../assets/logo.png'

const cart = useCartStore()
const router = useRouter()
const route = useRoute()
const cotizStore = useCotizacionesStore()
const ventasStore = useVentasStore()
const auth = useAuthStore()
const catalog = useCatalogStore()
const { showToast } = useToast()

const paso = ref(1) // 1 dirección, 2 pago, 3 confirmar
const procesando = ref(false)
const resultado = ref(null) // 'exito' | 'error' | null
const pedidoConfirmado = ref(null)

// Se prellena con la dirección que el cliente ya registró (mismo patrón que ya usa
// ContactoView.vue) — antes quedaba vacía y el cliente tenía que volver a escribirla siempre.
const direccion = reactive({
  nombre: auth.usuario ? `${auth.usuario.nombre} ${auth.usuario.apellido}` : '',
  telefono: auth.usuario?.telefono || '',
  cedula: auth.usuario?.numero_identificacion || '',
  departamento: auth.usuario?.departamento || 'Huila',
  ciudad: auth.usuario?.ciudad || '',
  direccionExacta: auth.usuario?.direccion || '',
  complemento: '',
})
const metodoEnvio = ref('envio')

// Elegir entre la dirección ya guardada en la cuenta (departamento/ciudad/dirección de `usuarios`)
// o escribir una distinta solo para este pedido. Si la cuenta no tiene dirección guardada todavía
// (cliente nuevo, esos 3 campos vacíos) no tiene sentido ofrecer esa opción - se arranca directo en
// "otra dirección", que es como se comportaba el checkout antes de este cambio.
const tieneDireccionGuardada = computed(() => !!(auth.usuario?.direccion && auth.usuario?.ciudad && auth.usuario?.departamento))
const fuenteDireccion = ref(tieneDireccionGuardada.value ? 'cuenta' : 'otra')

function elegirFuenteDireccion(valor) {
  fuenteDireccion.value = valor
  if (valor === 'cuenta') {
    direccion.departamento = auth.usuario?.departamento || 'Huila'
    direccion.ciudad = auth.usuario?.ciudad || ''
    direccion.direccionExacta = auth.usuario?.direccion || ''
    direccion.complemento = ''
  } else {
    direccion.departamento = 'Huila'
    direccion.ciudad = ''
    direccion.direccionExacta = ''
    direccion.complemento = ''
  }
}

const municipiosDisponibles = computed(() => MUNICIPIOS_POR_DEPARTAMENTO[direccion.departamento] || [])

watch(() => direccion.departamento, () => {
  if (!municipiosDisponibles.value.includes(direccion.ciudad)) {
    direccion.ciudad = ''
  }
})

const metodoPago = ref('tarjeta')
const tarjeta = reactive({ numero: '', vencimiento: '', cvv: '', nombre: '' })

// El producto siempre se paga aquí, de una vez, sin importar el método de envío - "contraentrega"
// no es una forma de pagar el pedido, es solo que el costo del ENVÍO (costoEnvio abajo) se paga
// aparte, directo a la transportadora al recibirlo. Por eso no se toca metodoPago según metodoEnvio.

// costoEnvio es solo informativo (para el aviso del Paso 1) - nunca se suma al total que cobra el
// sitio, porque ese costo no lo cobra Acabados 1A sino la transportadora al momento de la entrega.
const costoEnvio = computed(() => (metodoEnvio.value === 'recogida' ? 0 : cart.total >= 400000 ? 0 : 8000))
const total = computed(() => cart.total)

onMounted(() => {
  if (!cart.items.length && !resultado.value) {
    router.replace('/carrito')
  }
})

function irAPago() {
  paso.value = 2
}

function irAConfirmar() {
  paso.value = 3
}

async function confirmarPago() {
  procesando.value = true
  await new Promise((r) => setTimeout(r, 1400))
  procesando.value = false

  // La dirección de entrega no tiene columna propia en `ventas` (solo `notas_cliente`), así que
  // se empaca al inicio del texto con el mismo patrón que ya usan las cotizaciones para la fecha
  // deseada — así el admin sí puede ver a dónde entregar, sin inventar una columna nueva.
  const direccionLegible = metodoEnvio.value === 'recogida'
    ? 'Recogida en tienda'
    : `${direccion.nombre} · ${direccion.telefono} · ${direccion.direccionExacta}${direccion.complemento ? ', ' + direccion.complemento : ''}, ${direccion.ciudad}, ${direccion.departamento}`

  // El pago sigue siendo simulado (siempre aprueba), pero a partir de aquí la venta ya queda
  // guardada de verdad: resta stock y registra el movimiento 'venta' en Inventario. Se manda el
  // método canónico (coincide con el ENUM real de `ventas.metodo_pago`) - el texto con los
  // últimos 4 dígitos de la tarjeta es solo para la pantalla de confirmación, nunca se persiste.
  let venta
  try {
    venta = await ventasStore.crearVenta({
      id_usuario: auth.usuario?.id_usuario,
      items: cart.items,
      subtotal: cart.total,
      total: total.value,
      metodo_pago: metodoPago.value,
      metodo_envio: metodoEnvio.value,
      notas_cliente: empacarDireccionEntrega(direccionLegible, null),
    })
  } catch (e) {
    resultado.value = 'error'
    showToast(e.response?.data?.mensaje || 'No se pudo registrar el pedido. Intenta de nuevo.', 'danger')
    return
  }

  // Recién aquí, con la venta ya confirmada por el backend, se marca como éxito - antes se
  // marcaba antes de llamar a crearVenta(), así que la pantalla de confirmación podía llegar a
  // mostrarse mientras la petición real todavía estaba en curso (o incluso si fallaba).
  resultado.value = 'exito'
  pedidoConfirmado.value = {
    numero: venta.numero_venta,
    fecha: new Date().toLocaleDateString('es-CO', { year: 'numeric', month: 'long', day: 'numeric' }),
    total: total.value,
    metodo: metodoPago.value === 'tarjeta' ? `Tarjeta •••• ${tarjeta.numero.slice(-4) || '0000'}` : etiquetaMetodoPago(metodoPago.value),
    esEnvio: metodoEnvio.value === 'envio',
  }
  cart.vaciarCarrito()

  // Si el pago viene de una cotización aprobada (botón "Proceder al Pago"), la marcamos como
  // convertida. La venta ya quedó registrada arriba de todos modos, así que un fallo aquí no
  // deja al cliente sin su pedido — solo se avisa, no se revierte nada.
  if (route.query.fromCotizacion) {
    try {
      await cotizStore.actualizarEstado(Number(route.query.fromCotizacion), 'convertida_venta')
    } catch (e) {
      showToast(e.response?.data?.mensaje || 'El pedido quedó registrado, pero no se pudo actualizar la cotización asociada.', 'danger')
    }
  }
}

// Nombre para mostrar de cada valor del ENUM `metodo_pago` (antes la confirmación decía «nequi»).
const NOMBRES_METODO_PAGO = { tarjeta: 'Tarjeta', nequi: 'Nequi', daviplata: 'Daviplata', transferencia: 'Transferencia / Consignación', efectivo: 'Efectivo', contraentrega: 'Contraentrega' }
function etiquetaMetodoPago(valor) {
  return NOMBRES_METODO_PAGO[valor] || valor
}

// Dirección del local (tabla configuracion) para cuando el cliente elige recoger en tienda.
// La clave `direccion` ya trae el municipio («Carrera 5 No 6-31, Tesalia»): solo se agregan ciudad
// y departamento si no vienen ya en el texto, para no mostrar «Tesalia, Tesalia».
const direccionTienda = computed(() => {
  const { direccion = '', ciudad, departamento } = catalog.configuracion
  return [ciudad, departamento].filter((p) => p && !direccion.includes(p)).reduce((txt, p) => (txt ? `${txt}, ${p}` : p), direccion)
})

function reintentar() {
  resultado.value = null
  paso.value = 2
}
</script>

<template>
  <div class="checkout-page">
    <div class="checkout-topbar">
      <div class="checkout-topbar-inner">
        <div class="checkout-brand-group">
          <span class="checkout-logo"><img :src="logoUrl" alt="Acabados y Diseños 1A" /></span>
          <span class="checkout-brand">ACABADOS Y DISEÑOS <span class="checkout-brand-accent">1A</span></span>
        </div>
        <span class="checkout-secure"><i class="ri-shield-check-fill"></i> <span class="checkout-secure-text">Compra 100% segura</span></span>
        <RouterLink to="/carrito" class="checkout-close" aria-label="Cerrar"><i class="ri-close-line"></i></RouterLink>
      </div>
    </div>

    <div class="checkout-body">
      <!-- RESULTADO -->
      <template v-if="resultado === 'exito'">
        <div class="text-center" style="max-width:480px;margin:0 auto;">
          <div class="result-icon success"><i class="ri-check-line"></i></div>
          <span class="badge badge-green mb-16">¡Pedido Exitoso!</span>
          <h2 class="section-title">¡Tu pedido fue confirmado!</h2>
          <p class="section-subtitle">
            <template v-if="pedidoConfirmado.esEnvio">Gracias por tu compra. Te enviaremos tu pedido lo más pronto posible — la guía y todos los detalles llegarán a tu correo registrado.</template>
            <template v-else>Hemos recibido tu solicitud correctamente. Te enviaremos los detalles a tu correo registrado.</template>
          </p>

          <div class="result-box">
            <div class="d-flex justify-between mb-16">
              <strong class="font-main">Detalles del pedido</strong>
              <span class="badge badge-green">Confirmado</span>
            </div>
            <p class="text-muted" style="font-size:0.78rem;">NÚMERO DE PEDIDO</p>
            <p class="mb-16 font-main fw-700">{{ pedidoConfirmado.numero }}</p>
            <p class="text-muted" style="font-size:0.78rem;">FECHA</p>
            <p class="mb-16">{{ pedidoConfirmado.fecha }}</p>
            <p class="text-muted" style="font-size:0.78rem;">TOTAL PAGADO</p>
            <p class="mb-16 text-primary font-main fw-800">{{ formatCOP(pedidoConfirmado.total) }}</p>
            <p class="text-muted" style="font-size:0.78rem;">MÉTODO</p>
            <p>{{ pedidoConfirmado.metodo }}</p>
            <template v-if="pedidoConfirmado.esEnvio">
              <p class="text-muted" style="font-size:0.78rem;margin-top:16px;">ENVÍO</p>
              <p>Se paga aparte, directo a la transportadora al recibir tu pedido.</p>
            </template>
            <template v-else>
              <p class="text-muted" style="font-size:0.78rem;margin-top:16px;">RECOGIDA EN TIENDA</p>
              <p>{{ direccionTienda }}<template v-if="catalog.configuracion.horario"> · {{ catalog.configuracion.horario }}</template></p>
            </template>
          </div>

          <div class="d-flex gap-16" style="justify-content:center;flex-wrap:wrap;">
            <RouterLink to="/pedidos" class="btn btn-primary"><i class="ri-file-list-3-line"></i> Ver Historial de Pedidos</RouterLink>
            <RouterLink to="/productos" class="btn btn-outline-red"><i class="ri-store-line"></i> Seguir Comprando</RouterLink>
          </div>
        </div>
      </template>

      <!-- PROCESANDO -->
      <template v-else-if="procesando">
        <div class="text-center" style="max-width:420px;margin:80px auto;">
          <div class="result-icon" style="background:var(--light);color:var(--primary);">
            <i class="ri-loader-4-line" style="animation:spin 1s linear infinite;"></i>
          </div>
          <h2 class="section-title">Procesando tu pago...</h2>
          <p class="section-subtitle">Estamos verificando tu transacción de forma segura. No cierres esta ventana.</p>
        </div>
      </template>

      <!-- WIZARD -->
      <template v-else>
        <div class="steps">
          <div class="step" :class="{ active: paso === 1, done: paso > 1 }">
            <div class="step-dot" style="cursor:pointer;" @click="paso = 1"><i v-if="paso > 1" class="ri-check-line"></i><template v-else>1</template></div>
            <span class="step-label">Dirección</span>
          </div>
          <div class="step-line"></div>
          <div class="step" :class="{ active: paso === 2, done: paso > 2 }">
            <div class="step-dot" :style="paso > 2 ? 'cursor:pointer;' : ''" @click="paso > 2 && (paso = 2)"><i v-if="paso > 2" class="ri-check-line"></i><template v-else>2</template></div>
            <span class="step-label">Pago</span>
          </div>
          <div class="step-line"></div>
          <div class="step" :class="{ active: paso === 3 }">
            <div class="step-dot">3</div>
            <span class="step-label">Confirmar</span>
          </div>
        </div>

        <div class="checkout-layout">
          <div class="checkout-panel">
            <!-- PASO 1: DIRECCIÓN -->
            <template v-if="paso === 1">
              <h2 class="font-main fw-800 mb-24" style="font-size:1.4rem;">Dirección de entrega</h2>
              <form @submit.prevent="irAPago">
                <div class="form-group">
                  <label class="form-label required">Nombre completo</label>
                  <input v-model="direccion.nombre" class="form-control" required />
                </div>
                <div style="display:grid;grid-template-columns:1fr 1fr;gap:16px;">
                  <div class="form-group">
                    <label class="form-label required">Teléfono</label>
                    <input v-model="direccion.telefono" class="form-control" required />
                  </div>
                  <div class="form-group">
                    <label class="form-label required">Cédula / ID</label>
                    <input v-model="direccion.cedula" class="form-control" required />
                  </div>
                </div>
                <h3 class="font-main fw-700 mb-16" style="font-size:1rem;">¿A dónde entregamos?</h3>
                <div class="d-flex gap-16 mb-16" style="flex-wrap:wrap;">
                  <label v-if="tieneDireccionGuardada" class="shipping-option" :class="{ selected: fuenteDireccion === 'cuenta' }">
                    <span><input type="radio" :checked="fuenteDireccion === 'cuenta'" @change="elegirFuenteDireccion('cuenta')" /> Mi dirección guardada</span>
                  </label>
                  <label class="shipping-option" :class="{ selected: fuenteDireccion === 'otra' }">
                    <span><input type="radio" :checked="fuenteDireccion === 'otra'" @change="elegirFuenteDireccion('otra')" /> Entregar en otra dirección</span>
                  </label>
                </div>

                <div v-if="fuenteDireccion === 'cuenta'" class="mb-24" style="background:var(--off-white);border-radius:var(--radius-sm);padding:16px;">
                  <p style="font-size:0.9rem;line-height:1.6;">
                    {{ auth.usuario?.direccion }}<br />
                    {{ auth.usuario?.ciudad }}, {{ auth.usuario?.departamento }}
                  </p>
                  <RouterLink to="/perfil" class="text-primary" style="font-size:0.82rem;">¿No es correcta? Actualízala en tu perfil</RouterLink>
                </div>
                <template v-else>
                  <div style="display:grid;grid-template-columns:1fr 1fr;gap:16px;">
                    <div class="form-group">
                      <label class="form-label required">Departamento</label>
                      <select v-model="direccion.departamento" class="form-control" required>
                        <option value="" disabled>Seleccionar...</option>
                        <option v-for="depto in DEPARTAMENTOS" :key="depto" :value="depto">{{ depto }}</option>
                      </select>
                    </div>
                    <div class="form-group">
                      <label class="form-label required">Ciudad</label>
                      <select v-model="direccion.ciudad" class="form-control" required :disabled="!direccion.departamento">
                        <option value="" disabled>{{ direccion.departamento ? 'Seleccionar...' : 'Elige primero un departamento' }}</option>
                        <option v-for="mun in municipiosDisponibles" :key="mun" :value="mun">{{ mun }}</option>
                      </select>
                    </div>
                  </div>
                  <div class="form-group">
                    <label class="form-label required">Dirección exacta</label>
                    <input v-model="direccion.direccionExacta" class="form-control" placeholder="Calle 5 # 12-34, Barrio Centro" required />
                  </div>
                  <div class="form-group">
                    <label class="form-label">Complemento (opcional)</label>
                    <input v-model="direccion.complemento" class="form-control" placeholder="Apto, oficina, piso..." />
                  </div>
                </template>

                <h3 class="font-main fw-700 mb-16" style="font-size:1rem;">Método de envío</h3>
                <div class="alert alert-success mb-16">
                  <i class="ri-truck-line"></i>
                  <span>Por compras superiores a $400.000, el envío es gratis a todo el país.</span>
                </div>
                <div class="d-flex gap-16 mb-16" style="flex-wrap:wrap;">
                  <label class="shipping-option" :class="{ selected: metodoEnvio === 'envio' }">
                    <span><input v-model="metodoEnvio" type="radio" value="envio" /> Envío contraentrega</span>
                  </label>
                  <label class="shipping-option" :class="{ selected: metodoEnvio === 'recogida' }">
                    <span><input v-model="metodoEnvio" type="radio" value="recogida" /> Recogida en tienda</span>
                  </label>
                </div>
                <div v-if="metodoEnvio === 'envio'" class="alert alert-warning mb-24" style="flex-direction:column;align-items:stretch;">
                  <div style="display:flex;gap:10px;">
                    <i class="ri-error-warning-line"></i>
                    <span>
                      El producto lo pagas ahora, en el siguiente paso. El costo del envío se paga aparte, directo a la transportadora cuando te entreguen el pedido.
                      Te enviaremos la guía a tu correo registrado apenas lo despachemos.
                    </span>
                  </div>
                  <a :href="`https://wa.me/${catalog.configuracion.whatsapp}?text=Hola!%20Tengo%20una%20duda%20sobre%20mi%20pedido`" target="_blank" rel="noopener" class="btn btn-success" style="margin-top:10px;align-self:flex-start;">
                    <i class="ri-whatsapp-line"></i> ¿Dudas? Escríbenos al {{ catalog.configuracion.telefono }}
                  </a>
                </div>
                <p v-else class="text-muted mb-24" style="font-size:0.8rem;display:flex;align-items:center;gap:8px;">
                  <i class="ri-mail-line text-primary"></i>
                  <span>Te enviaremos la confirmación de tu pedido a tu correo registrado.</span>
                </p>

                <button type="submit" class="btn btn-primary btn-lg btn-block">Continuar al Método de Pago <i class="ri-arrow-right-line"></i></button>
              </form>
            </template>

            <!-- PASO 2: PAGO -->
            <template v-else-if="paso === 2">
              <h2 class="font-main fw-800 mb-24" style="font-size:1.4rem;">Método de pago</h2>
              <form @submit.prevent="irAConfirmar">
                <label class="payment-method" :class="{ selected: metodoPago === 'tarjeta' }">
                  <span class="payment-method-left"><input v-model="metodoPago" type="radio" value="tarjeta" /> Tarjeta de crédito o débito</span>
                  <i class="ri-bank-card-line"></i>
                </label>
                <div v-if="metodoPago === 'tarjeta'" style="padding:0 20px 16px;">
                  <div class="form-group">
                    <label class="form-label required">Número de tarjeta</label>
                    <input v-model="tarjeta.numero" class="form-control" placeholder="•••• •••• •••• 4582" maxlength="19" inputmode="numeric" required />
                  </div>
                  <div style="display:grid;grid-template-columns:1fr 1fr;gap:16px;">
                    <div class="form-group">
                      <label class="form-label required">Vencimiento</label>
                      <input v-model="tarjeta.vencimiento" class="form-control" placeholder="MM/YY" pattern="(0[1-9]|1[0-2])/[0-9]{2}" title="Formato MM/YY" required />
                    </div>
                    <div class="form-group">
                      <label class="form-label required">CVV</label>
                      <input v-model="tarjeta.cvv" class="form-control" placeholder="***" maxlength="4" inputmode="numeric" pattern="[0-9]{3,4}" title="3 o 4 dígitos" required />
                    </div>
                  </div>
                  <div class="form-group">
                    <label class="form-label required">Nombre en la tarjeta</label>
                    <input v-model="tarjeta.nombre" class="form-control" required />
                  </div>
                </div>

                <label class="payment-method" :class="{ selected: metodoPago === 'nequi' }">
                  <span class="payment-method-left"><input v-model="metodoPago" type="radio" value="nequi" /> Nequi</span>
                  <i class="ri-smartphone-line"></i>
                </label>
                <label class="payment-method" :class="{ selected: metodoPago === 'daviplata' }">
                  <span class="payment-method-left"><input v-model="metodoPago" type="radio" value="daviplata" /> Daviplata</span>
                  <i class="ri-wallet-3-line"></i>
                </label>
                <label class="payment-method" :class="{ selected: metodoPago === 'transferencia' }">
                  <span class="payment-method-left"><input v-model="metodoPago" type="radio" value="transferencia" /> Transferencia / Consignación</span>
                  <i class="ri-bank-line"></i>
                </label>

                <p class="text-muted mb-24" style="font-size:0.8rem;display:flex;align-items:center;gap:8px;margin-top:12px;">
                  <i class="ri-information-line text-primary"></i>
                  <!-- Antes decía «protegida con cifrado SSL de 256 bits», que no es cierto en local (sin
                       HTTPS) ni aplica a un pago simulado. La pasarela real (Wompi) está pendiente. -->
                  <span>Pago simulado: no se realiza ningún cobro real. La pasarela de pagos se conectará al publicar la tienda.</span>
                </p>

                <div class="d-flex gap-12">
                  <button type="button" class="btn btn-outline-red" @click="paso = 1"><i class="ri-arrow-left-line"></i> Volver</button>
                  <button type="submit" class="btn btn-primary btn-block">Revisar Pedido <i class="ri-arrow-right-line"></i></button>
                </div>
              </form>
            </template>

            <!-- PASO 3: CONFIRMAR -->
            <template v-else>
              <h2 class="font-main fw-800 mb-24" style="font-size:1.4rem;">Revisa tu pedido</h2>

              <div class="d-flex justify-between align-center mb-16">
                <strong class="font-main">
                  <i :class="metodoEnvio === 'recogida' ? 'ri-store-2-line' : 'ri-map-pin-line'" class="text-primary"></i>
                  {{ metodoEnvio === 'recogida' ? 'Recogida en tienda' : 'Dirección de entrega' }}
                </strong>
                <a href="#" class="text-primary" style="font-size:0.85rem;" @click.prevent="paso = 1">Editar</a>
              </div>
              <!-- Con recogida en tienda se muestra el local, no la dirección del cliente (antes salía su
                   dirección aunque el pedido no se iba a enviar). -->
              <p v-if="metodoEnvio === 'recogida'" class="mb-24">{{ direccion.nombre }}<br />Recoges tu pedido en: {{ direccionTienda }}<template v-if="catalog.configuracion.horario"><br />{{ catalog.configuracion.horario }}</template></p>
              <p v-else class="mb-24">{{ direccion.nombre }}<br />{{ direccion.direccionExacta }}<br />{{ direccion.ciudad }}, {{ direccion.departamento }}</p>

              <div class="d-flex justify-between align-center mb-16">
                <strong class="font-main"><i class="ri-bank-card-line text-primary"></i> Método de pago</strong>
                <a href="#" class="text-primary" style="font-size:0.85rem;" @click.prevent="paso = 2">Editar</a>
              </div>
              <p class="mb-24">{{ metodoPago === 'tarjeta' ? `Tarjeta terminada en ${tarjeta.numero.slice(-4) || '----'}` : etiquetaMetodoPago(metodoPago) }}</p>

              <strong class="font-main">Productos ({{ cart.cantidadTotal }})</strong>
              <div v-for="item in cart.items" :key="item.id_producto" class="cart-item">
                <div class="cart-item-img"><img :src="item.imagen_url" :alt="item.nombre" /></div>
                <div class="cart-item-info">
                  <div class="cart-item-name">{{ item.nombre }}</div>
                  <div class="cart-item-cat">Cantidad: {{ item.cantidad }}</div>
                </div>
                <div class="cart-item-price">{{ formatCOP(item.precio_venta * item.cantidad) }}</div>
              </div>

              <div class="d-flex gap-12 mt-20">
                <button type="button" class="btn btn-outline-red" @click="paso = 2"><i class="ri-arrow-left-line"></i> Volver</button>
                <button type="button" class="btn btn-primary btn-block" @click="confirmarPago">
                  <i class="ri-lock-line"></i> Confirmar y Pagar {{ formatCOP(total) }}
                </button>
              </div>
            </template>
          </div>

          <aside class="order-summary">
            <h3 class="font-main fw-800 mb-16">Resumen de compra</h3>
            <div v-for="item in cart.items" :key="item.id_producto" class="d-flex justify-between mb-16" style="font-size:0.85rem;">
              <span>{{ item.nombre }} <span class="text-muted">x{{ item.cantidad }}</span></span>
              <span class="fw-700">{{ formatCOP(item.precio_venta * item.cantidad) }}</span>
            </div>
            <div class="order-summary-row"><span>Subtotal</span><span>{{ formatCOP(cart.total) }}</span></div>
            <div v-if="metodoEnvio === 'recogida'" class="order-summary-row"><span>Envío</span><span>Gratis</span></div>
            <div class="order-summary-row total"><span>Total</span><span class="value">{{ formatCOP(total) }}</span></div>
            <p v-if="metodoEnvio === 'envio'" class="text-muted" style="font-size:0.78rem;margin-top:-8px;">El envío se paga aparte, directo a la transportadora al recibir tu pedido.</p>
          </aside>
        </div>
      </template>
    </div>
  </div>
</template>

<style scoped>
@keyframes spin { from { transform: rotate(0); } to { transform: rotate(360deg); } }
</style>
