<script setup>
// RF06 - detalle de producto: galería, atributos, tabs (descripción/especificaciones/reseñas/FAQ), relacionados
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useCatalogStore } from '../stores/catalog'
import { useCartStore } from '../stores/cart'
import { useAuthStore } from '../stores/auth'
import { useResenasStore } from '../stores/resenas'
import { useToast } from '../composables/useToast'
import { formatCOP, formatUnidadMedida } from '../composables/useFormat'
import ProductCard from '../components/product/ProductCard.vue'
import CalculadoraPinturaModal from '../components/product/CalculadoraPinturaModal.vue'
import SelectorColorModal from '../components/product/SelectorColorModal.vue'
import CotizarLoginModal from '../components/service/CotizarLoginModal.vue'
import { useCotizarGate } from '../composables/useCotizarGate'

const props = defineProps({ id: { type: String, required: true } })
const catalog = useCatalogStore()
const cart = useCartStore()
const auth = useAuthStore()
const resenasStore = useResenasStore()
const router = useRouter()
const { showToast } = useToast()
const { mostrarModalLogin: mostrarModalLoginCotizar, irACotizar, irALogin: irALoginCotizar } = useCotizarGate()

onMounted(() => {
  resenasStore.cargarResenas()
})

const producto = computed(() => catalog.getProductById(props.id))
const cantidad = ref(1)
const tabActiva = ref('descripcion')
const mostrarCalculadora = ref(false)

const variantesTamano = computed(() => (producto.value ? catalog.getVariantesTamano(producto.value) : []))

// El mismo mecanismo de variantes (grupo_variante/tamano) sirve tanto para tamaños (pinturas)
// como para colores (ej. Graniplast): si el color cambia entre hermanos, es un selector de color.
// Excepción: si las etiquetas del selector traen una medida (galón, cuñete...), es de tamaño
// aunque el `color` difiera entre variantes — ese `color` ahí es solo informativo (ej. esmalte
// MAX Co.: "½ Galón" en 3 colores vs "½ Galón Aluminio").
const RE_MEDIDA_TAMANO = /gal[oó]n|cu[ñn]ete|bolsa|litro|kilo|\bkg\b|cuarto|½|¼/i
const esVarianteColor = computed(() => {
  const variantes = variantesTamano.value
  if (variantes.length < 2) return false
  if (variantes.some((v) => RE_MEDIDA_TAMANO.test(v.tamano || ''))) return false
  return new Set(variantes.map((v) => v.color)).size > 1
})

const COLOR_SWATCHES = {
  blanco: '#ffffff', beige: '#e8dcc4', terracota: '#c56a4a', gris: '#9a9a9a',
  'gris piedra': '#8d8d86', negro: '#1a1a1a', azul: '#5b8fc7', 'azul pastel': '#b8d4e8',
  verde: '#6b9b5e', 'verde menta': '#a8d5ba', rojo: '#b33a3a', amarillo: '#e0c14a',
  crema: '#f0e6d2', transparente: '#e8e8e8', galvanizado: '#b0b0b0', aluminio: '#c8ccce',
}
function colorSwatch(nombreColor) {
  return COLOR_SWATCHES[(nombreColor || '').toLowerCase()] || null
}

function cambiarTamano(id_producto) {
  router.push(`/productos/${id_producto}`)
}

const galeria = computed(() => producto.value ? [producto.value.imagen_url, producto.value.imagen_url, producto.value.imagen_url] : [])
const imagenActiva = ref(0)

// El selector de color solo aplica a Pinturas y Vinilos — para el resto de categorías el
// atributo "Color" es solo informativo, no un catálogo de tonos para elegir. Por nombre, no por
// id_categoria (el backend real no numera las categorías igual que el mock).
const esProductoPintura = computed(() => catalog.getCategoryName(producto.value?.id_categoria) === 'Pinturas y Vinilos')
const colorElegido = ref(null)
const mostrarSelectorColor = ref(false)
function onColorConfirmado(color) {
  colorElegido.value = color
  mostrarSelectorColor.value = false
}

// Referencia visual: el envase se ve un poco más grande/pequeño según el tamaño elegido, para dar
// una idea de proporción entre 1/4 galón y un cuñete (mismo mecanismo de variantes de tamaño).
// Las llaves van en minúscula porque se comparan contra `tamano.toLowerCase()`.
// Rango suave (0.82–1.13): con object-fit:contain el envase siempre se ve completo y solo
// cambia de tamaño dentro del marco — un salto grande (1.3) recortaba el producto y se veía raro.
const ESCALA_POR_TAMANO = {
  '¼ galón': 0.82, '1/4 galón': 0.82,
  '½ galón': 0.9, '1/2 galón': 0.9,
  'galón': 1, '1 galón': 1,
  '½ bolsa': 1.04, 'media bolsa': 1.04,
  '½ cuñete': 1.06, 'medio cuñete': 1.06,
  'bolsa': 1.12, 'bolsa 28 kg': 1.12, '1 bolsa': 1.12,
  'cuñete': 1.13, 'cuñete 28 kg': 1.13, '1 cuñete': 1.13,
}
// El producto que se está viendo no trae `tamano` (eso vive en gruposVariante, no en la fila
// del producto) — se toma de la variante correspondiente dentro del grupo.
const varianteActual = computed(() =>
  variantesTamano.value.find((v) => v.id_producto === producto.value?.id_producto),
)
const escalaImagen = computed(() => {
  const tamano = (varianteActual.value?.tamano || '').toLowerCase()
  return ESCALA_POR_TAMANO[tamano] || 1
})

const stock = computed(() => producto.value ? catalog.getProductStock(producto.value.id_producto) : 0)
const stockStatus = computed(() => producto.value ? catalog.getStockStatus(producto.value.id_producto) : null)
// Un producto eliminado (borrado lógico) no sale en el catálogo, pero su enlace directo sigue
// abriendo esta página: se muestra como no disponible y no se deja comprar (el backend también
// lo rechaza en VentaService).
const productoInactivo = computed(() => producto.value?.activo === false)
const sePuedeComprar = computed(() => !productoInactivo.value && stock.value > 0)

const promoActiva = computed(() => producto.value ? catalog.getActivePromoForProduct(producto.value.id_producto) : null)

// Cotizar al por mayor solo tiene sentido para las líneas que la empresa fabrica/aplica ella misma
// (ver catalog.js: NOMBRES_FABRICACION_PROPIA) — no en Drywall/PVC ni Herramientas, que son
// productos de terceros que solo se revenden.
const esFabricacionPropia = computed(() => producto.value && catalog.esFabricacionPropia(producto.value))
const precioConDescuento = computed(() => {
  if (!promoActiva.value || !producto.value) return null
  return Math.round(producto.value.precio_venta * (1 - promoActiva.value.descuento_porcentaje / 100))
})

// Un combo no cambia el precio individual de este producto (ese sigue siendo precio_venta) - es
// un panel informativo aparte con el precio del paquete completo, no un descuento por unidad.
const comboActivo = computed(() => producto.value ? catalog.getComboForProduct(producto.value.id_producto) : null)
const productosCombo = computed(() => {
  if (!comboActivo.value) return []
  return comboActivo.value.productos.map((id) => catalog.getProductById(id)).filter(Boolean)
})
const ahorroCombo = computed(() => {
  if (!comboActivo.value) return 0
  const sumaIndividual = productosCombo.value.reduce((acc, p) => acc + Number(p.precio_venta), 0)
  return Math.max(0, sumaIndividual - Number(comboActivo.value.precio_especial))
})

const atributos = computed(() => {
  if (!producto.value) return []
  const p = producto.value
  const list = []
  if (p.marca) list.push({ label: 'Marca', value: p.marca, icon: 'ri-award-line' })
  if (p.presentacion) list.push({ label: 'Presentación', value: p.presentacion, icon: 'ri-archive-line' })
  if (p.color && p.color !== 'N/A') list.push({ label: 'Color', value: p.color, icon: 'ri-palette-line' })
  if (p.acabado && p.acabado !== 'N/A') list.push({ label: 'Acabado', value: p.acabado, icon: 'ri-sparkling-line' })
  if (p.unidad_medida) list.push({ label: 'Unidad', value: formatUnidadMedida(p.unidad_medida), icon: 'ri-scales-3-line' })
  if (p.material && p.material !== 'N/A') list.push({ label: 'Material', value: p.material, icon: 'ri-stack-line' })
  return list
})

const especificacionesCompletas = computed(() => {
  if (!producto.value) return []
  const p = producto.value
  const rows = [{ label: 'Código', value: p.codigo_producto }]
  if (p.marca) rows.push({ label: 'Marca', value: p.marca })
  if (p.modelo) rows.push({ label: 'Modelo', value: p.modelo })
  if (p.unidad_medida) rows.push({ label: 'Unidad de medida', value: formatUnidadMedida(p.unidad_medida) })
  if (p.presentacion) rows.push({ label: 'Presentación', value: p.presentacion })
  if (p.color && p.color !== 'N/A') rows.push({ label: 'Color', value: p.color })
  if (p.acabado && p.acabado !== 'N/A') rows.push({ label: 'Acabado', value: p.acabado })
  if (p.material && p.material !== 'N/A') rows.push({ label: 'Material', value: p.material })
  if (p.dimensiones) rows.push({ label: 'Dimensiones', value: p.dimensiones })
  if (p.peso_kg) rows.push({ label: 'Peso', value: `${p.peso_kg} kg` })
  rows.push({ label: 'Precio de venta', value: formatCOP(p.precio_venta) })
  if (p.precio_mayorista) rows.push({ label: 'Precio mayorista', value: `${formatCOP(p.precio_mayorista)} (compras +10 unid.)` })
  rows.push({ label: 'Categoría', value: catalog.getCategoryName(p.id_categoria) })
  return rows
})

const relacionados = computed(() => {
  if (!producto.value) return []
  // Excluye toda la familia de variantes del producto actual (si no, "Relacionados" mostraría
  // el mismo artículo en sus otros tamaños, que ya están en el selector de arriba) y usa
  // productosCatalogo para no repetir las variantes ocultas.
  const idsFamilia = new Set(variantesTamano.value.map((v) => v.id_producto))
  idsFamilia.add(producto.value.id_producto)
  return catalog.productosCatalogo
    .filter((p) => p.id_categoria === producto.value.id_categoria && !idsFamilia.has(p.id_producto))
    .slice(0, 4)
})

// ¿Nuestro equipo puede aplicar/instalar este producto? Servicios sugeridos según su categoría.
const serviciosSugeridos = computed(() => {
  if (!producto.value) return []
  return catalog.getServiciosSugeridos([producto.value.id_categoria]).slice(0, 3)
})

function agregarAlCarrito() {
  if (!sePuedeComprar.value) {
    showToast(productoInactivo.value ? 'Este producto ya no está disponible' : 'Producto agotado', 'danger')
    return
  }
  cart.agregarProducto(producto.value, cantidad.value)
}

function comprarAhora() {
  if (!sePuedeComprar.value) {
    showToast(productoInactivo.value ? 'Este producto ya no está disponible' : 'Producto agotado', 'danger')
    return
  }
  cart.agregarProducto(producto.value, cantidad.value)
  router.push('/checkout')
}

// Enlace de esta página para compartir (se recalcula al cambiar de variante, que cambia la URL).
const urlProducto = computed(() => (producto.value ? `${window.location.origin}/productos/${producto.value.id_producto}` : window.location.href))
const enlaceWhatsApp = computed(() => `https://wa.me/?text=${encodeURIComponent(`${producto.value?.nombre || ''} — ${urlProducto.value}`)}`)
const enlaceFacebook = computed(() => `https://www.facebook.com/sharer/sharer.php?u=${encodeURIComponent(urlProducto.value)}`)

function copiarEnlace() {
  navigator.clipboard.writeText(window.location.href).then(() => showToast('Enlace copiado al portapapeles', 'success'))
}

// ── Reseñas ──────────────────────────────────────────────────
const misResenas = computed(() => producto.value ? resenasStore.getResenasDeProducto(producto.value.id_producto) : [])
const totalResenas = computed(() => misResenas.value.length)
const promedioResenas = computed(() => producto.value ? resenasStore.getPromedio(producto.value.id_producto) : 0)
const distribucionResenas = computed(() => producto.value ? resenasStore.getDistribucion(producto.value.id_producto) : {})

function nombreUsuario(usuario) {
  return usuario ? `${usuario.nombre} ${usuario.apellido}` : 'Usuario'
}
function inicialesUsuario(usuario) {
  return usuario ? (usuario.nombre[0] + (usuario.apellido?.[0] || '')) : 'U'
}

function irAResenas() {
  tabActiva.value = 'resenas'
  document.querySelector('.detalle-tabs')?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

const yaReseno = computed(() => (producto.value && auth.usuario ? resenasStore.yaReseno(producto.value.id_producto, auth.usuario.id_usuario) : false))
const mostrarModalLoginResena = ref(false)
const nuevaResena = ref({ calificacion: 0, comentario: '' })
const enviandoResena = ref(false)

function abrirFormularioResena() {
  if (!auth.isAuthenticated) mostrarModalLoginResena.value = true
}
function irALoginResena() {
  mostrarModalLoginResena.value = false
  router.push({ path: '/login', query: { redirect: `/productos/${props.id}` } })
}

async function publicarResena() {
  if (!nuevaResena.value.calificacion) {
    showToast('Selecciona una calificación.', 'danger')
    return
  }
  if (!nuevaResena.value.comentario.trim()) {
    showToast('Escribe un comentario para tu reseña.', 'danger')
    return
  }
  enviandoResena.value = true
  try {
    await resenasStore.crearResena({
      id_producto: producto.value.id_producto,
      calificacion: nuevaResena.value.calificacion,
      comentario: nuevaResena.value.comentario.trim(),
    })
    nuevaResena.value = { calificacion: 0, comentario: '' }
    showToast('¡Gracias por tu reseña!', 'success')
  } catch (e) {
    showToast(e.response?.data?.mensaje || 'No se pudo publicar la reseña.', 'danger')
  } finally {
    enviandoResena.value = false
  }
}

// ── Preguntas frecuentes ─────────────────────────────────────
const preguntasFrecuentes = computed(() => {
  if (!producto.value) return []
  const p = producto.value
  const impuesto = catalog.impuestos.find((i) => i.id_impuesto === p.id_impuesto)
  const ivaTexto = impuesto && impuesto.valor === 0
    ? 'Este producto está exento de IVA. El precio mostrado es el precio final.'
    : `El precio incluye ${impuesto ? impuesto.nombre : 'IVA'} según la tarifa vigente.`
  return [
    { pregunta: '¿Cuánto tiempo demora la entrega?', respuesta: 'Las entregas en Tesalia y Pitalito se realizan en 24 horas hábiles. Para otros municipios del Huila el tiempo es de 24 a 48 horas.' },
    { pregunta: '¿Puedo devolver el producto si no me satisface?', respuesta: 'Sí. Tienes 30 días desde la fecha de compra para solicitar un cambio si el producto está sin abrir y en perfectas condiciones.' },
    { pregunta: '¿El precio incluye IVA?', respuesta: ivaTexto },
    {
      pregunta: '¿Ofrecen descuentos por volumen?',
      respuesta: esFabricacionPropia.value
        ? 'Sí. Este producto lo fabricamos nosotros, así que para compras al por mayor solicita tu cotización mayorista y te confirmamos el mejor precio.'
        : 'Este producto lo distribuimos de otras marcas, así que no manejamos precio mayorista propio para él. Escríbenos y con gusto te ayudamos a conseguir el mejor precio posible.',
    },
    { pregunta: '¿Cómo calculo la cantidad que necesito?', respuesta: 'En la pestaña "Especificaciones" encontrarás el rendimiento del producto. También puedes escribirnos y nuestros asesores te ayudan a calcular la cantidad exacta.' },
  ]
})
const faqAbierta = ref(null)
function toggleFaq(i) {
  faqAbierta.value = faqAbierta.value === i ? null : i
}
</script>

<template>
  <template v-if="producto">
    <div class="container">
      <div class="breadcrumb">
        <span class="breadcrumb-item"><RouterLink to="/"><i class="ri-home-4-line"></i> Inicio</RouterLink></span>
        <span class="breadcrumb-sep"><i class="ri-arrow-right-s-line"></i></span>
        <span class="breadcrumb-item"><RouterLink to="/productos"><i class="ri-price-tag-3-line"></i> Catálogo</RouterLink></span>
        <span class="breadcrumb-sep"><i class="ri-arrow-right-s-line"></i></span>
        <span class="breadcrumb-item active">{{ producto.nombre }}</span>
      </div>
    </div>

    <section class="section" style="padding-top:0;">
      <div class="container">
        <div class="detalle-grid">
          <div>
            <div class="detalle-galeria-principal">
              <img
                :src="galeria[imagenActiva]"
                :alt="producto.nombre"
                :class="{ 'es-variante-tamano': !esVarianteColor && variantesTamano.length > 1 }"
                :style="!esVarianteColor && variantesTamano.length > 1 ? { transform: `scale(${escalaImagen})` } : {}"
              />
              <span v-if="promoActiva" class="badge badge-yellow detalle-oferta-badge">
                <i class="ri-price-tag-3-fill"></i> ¡En promoción! -{{ promoActiva.descuento_porcentaje }}%
              </span>
              <span v-else-if="comboActivo" class="badge badge-red detalle-oferta-badge">
                <i class="ri-gift-fill"></i> Parte de un combo
              </span>
            </div>
            <div class="detalle-galeria-thumbs">
              <button
                v-for="(img, i) in galeria"
                :key="i"
                class="detalle-thumb"
                :class="{ active: i === imagenActiva }"
                @click="imagenActiva = i"
              >
                <img :src="img" :alt="`${producto.nombre} ${i + 1}`" />
              </button>
            </div>

            <div class="detalle-share-bar">
              <span>Compartir:</span>
              <!-- Antes WhatsApp compartía solo el nombre y Facebook abría sharer.php sin ?u=, así que
                   ninguno llevaba el enlace del producto. -->
              <a :href="enlaceWhatsApp" target="_blank" rel="noopener" class="detalle-share-btn whatsapp" aria-label="Compartir por WhatsApp"><i class="ri-whatsapp-line"></i></a>
              <a :href="enlaceFacebook" target="_blank" rel="noopener" class="detalle-share-btn facebook" aria-label="Compartir en Facebook"><i class="ri-facebook-fill"></i></a>
              <button type="button" class="btn btn-outline-red btn-sm" @click="copiarEnlace"><i class="ri-link"></i> Copiar enlace</button>
            </div>
          </div>

          <div>
            <div class="product-category">{{ catalog.getCategoryName(producto.id_categoria) }} &middot; Cód: {{ producto.codigo_producto }}</div>
            <h1 class="detalle-titulo">{{ producto.nombre }}</h1>

            <div class="detalle-rating" @click="irAResenas">
              <span class="detalle-rating-stars">
                <i v-for="n in 5" :key="n" :class="n <= Math.round(promedioResenas) ? 'ri-star-fill' : 'ri-star-line'"></i>
              </span>
              <span v-if="totalResenas" class="detalle-rating-count">{{ promedioResenas.toFixed(1) }} · {{ totalResenas }} reseña{{ totalResenas === 1 ? '' : 's' }}</span>
              <span v-else class="detalle-rating-count">Sé el primero en opinar</span>
            </div>

            <div class="detalle-precio-box">
              <template v-if="promoActiva">
                <span class="detalle-precio">{{ formatCOP(precioConDescuento) }}</span>
                <span class="product-price-old">{{ formatCOP(producto.precio_venta) }}</span>
              </template>
              <span v-else class="detalle-precio">{{ formatCOP(producto.precio_venta) }}</span>
            </div>

            <div v-if="comboActivo" class="combo-panel">
              <div class="combo-panel-header">
                <i class="ri-gift-2-fill"></i>
                <div>
                  <strong>{{ comboActivo.titulo }}</strong>
                  <p v-if="comboActivo.descripcion">{{ comboActivo.descripcion }}</p>
                </div>
              </div>
              <div class="combo-panel-items">
                <RouterLink
                  v-for="cp in productosCombo"
                  :key="cp.id_producto"
                  :to="`/productos/${cp.id_producto}`"
                  class="combo-panel-item"
                  :class="{ activo: cp.id_producto === producto.id_producto }"
                >
                  <img :src="cp.imagen_url" :alt="cp.nombre" />
                  <span>{{ cp.nombre }}</span>
                </RouterLink>
              </div>
              <div class="combo-panel-price">
                <span class="combo-panel-total"><strong>{{ formatCOP(comboActivo.precio_especial) }}</strong> por el combo completo</span>
                <span v-if="ahorroCombo > 0" class="combo-panel-ahorro">Ahorras {{ formatCOP(ahorroCombo) }}</span>
              </div>
            </div>

            <div v-if="variantesTamano.length > 1" class="detalle-variantes">
              <div class="detalle-variantes-label">{{ esVarianteColor ? 'Elige un color:' : 'Elige un tamaño:' }}</div>
              <div class="filter-chips">
                <button
                  v-for="v in variantesTamano"
                  :key="v.id_producto"
                  class="filter-chip"
                  :class="{ active: v.id_producto === producto.id_producto }"
                  @click="cambiarTamano(v.id_producto)"
                >
                  <span v-if="esVarianteColor && colorSwatch(v.color)" class="detalle-color-swatch" :style="{ background: colorSwatch(v.color) }"></span>
                  {{ v.tamano }}
                </button>
              </div>
            </div>

            <button
              v-if="producto.unidad_medida === 'galon'"
              type="button"
              class="calc-trigger-btn"
              @click="mostrarCalculadora = true"
            >
              <i class="ri-calculator-line"></i> ¿Cuánta pintura necesito? Calcula la cantidad
            </button>

            <div class="detalle-attrs-grid">
              <template v-for="a in atributos" :key="a.label">
                <button
                  v-if="a.label === 'Color' && esProductoPintura"
                  type="button"
                  class="detalle-attr detalle-attr-color"
                  @click="mostrarSelectorColor = true"
                >
                  <div class="detalle-attr-label"><i :class="a.icon"></i> Color</div>
                  <div class="detalle-attr-value detalle-attr-color-value">
                    <span v-if="colorElegido" class="detalle-attr-color-swatch" :style="{ backgroundColor: colorElegido.hex }"></span>
                    <span>{{ colorElegido ? `${colorElegido.nombre} · ${colorElegido.codigo}` : a.value }}</span>
                    <i class="ri-arrow-right-s-line"></i>
                  </div>
                </button>
                <div
                  v-else-if="a.label === 'Color' && !esProductoPintura"
                  class="detalle-attr detalle-attr-colores"
                >
                  <div class="detalle-attr-label"><i :class="a.icon"></i> Colores disponibles</div>
                  <div class="detalle-attr-colores-list">
                    <span v-for="c in a.value.split(',').map((s) => s.trim())" :key="c" class="detalle-color-pill">
                      <span class="detalle-color-swatch" :style="{ background: colorSwatch(c) || '#ccc' }"></span>{{ c }}
                    </span>
                  </div>
                </div>
                <div v-else class="detalle-attr">
                  <div class="detalle-attr-label"><i :class="a.icon"></i> {{ a.label }}</div>
                  <div class="detalle-attr-value">{{ a.value }}</div>
                </div>
              </template>
            </div>

            <div v-if="productoInactivo" class="detalle-stock-box stock-out">
              <span class="detalle-stock-dot"></span>
              <div>
                <div class="detalle-stock-text">Producto no disponible</div>
                <div class="detalle-stock-hint" style="color: var(--danger);">Ya no hace parte de nuestro catálogo. Explora productos similares más abajo.</div>
              </div>
            </div>
            <div v-else class="detalle-stock-box" :class="stockStatus.class">
              <span class="detalle-stock-dot"></span>
              <div>
                <div class="detalle-stock-text">{{ stockStatus.label }} — {{ stock }} unidades disponibles</div>
                <div v-if="stockStatus.class === 'stock-low'" class="detalle-stock-hint">¡Pocas unidades! Asegura el tuyo ahora.</div>
              </div>
            </div>

            <div class="d-flex align-center gap-16 mb-24" style="margin-top:20px;">
              <span class="font-main fw-700">Cantidad:</span>
              <div class="qty-control">
                <button class="qty-btn" @click="cantidad = Math.max(1, cantidad - 1)">-</button>
                <input class="qty-input" type="number" min="1" v-model.number="cantidad" />
                <button class="qty-btn" @click="cantidad++">+</button>
              </div>
            </div>

            <div class="d-flex" style="flex-direction:column;gap:12px;">
              <button class="btn btn-primary btn-lg btn-block" :disabled="!sePuedeComprar" @click="agregarAlCarrito">
                <i class="ri-shopping-cart-line"></i> Agregar al Carrito
              </button>
              <button class="btn btn-dark btn-lg btn-block" :disabled="!sePuedeComprar" @click="comprarAhora">
                Comprar Ahora
              </button>
              <button
                v-if="esFabricacionPropia && !productoInactivo"
                type="button"
                class="btn btn-outline-red btn-lg btn-block"
                @click="irACotizar({ tipo: 'producto', id: producto.id_producto, mayorista: true })"
              >
                <i class="ri-price-tag-3-line"></i> Solicita tu Cotización Mayorista
              </button>
            </div>

            <div class="detalle-trust">
              <span><i class="ri-truck-line"></i> Entrega en Tesalia y el Huila en 24–48h</span>
              <span><i class="ri-shield-check-line"></i> Garantía de 30 días</span>
              <span><i class="ri-bank-card-line"></i> Pago seguro: tarjeta, Nequi, transferencia</span>
            </div>
          </div>
        </div>

        <div class="detalle-tabs">
          <div class="detalle-tabs-nav">
            <button :class="{ active: tabActiva === 'descripcion' }" @click="tabActiva = 'descripcion'">Descripción</button>
            <button :class="{ active: tabActiva === 'especificaciones' }" @click="tabActiva = 'especificaciones'">Especificaciones</button>
            <button :class="{ active: tabActiva === 'resenas' }" @click="tabActiva = 'resenas'">Reseñas ({{ totalResenas }})</button>
            <button :class="{ active: tabActiva === 'preguntas' }" @click="tabActiva = 'preguntas'">Preguntas frecuentes</button>
          </div>

          <div class="detalle-tabs-body">
            <!-- DESCRIPCIÓN -->
            <div v-if="tabActiva === 'descripcion'" class="detalle-descripcion">
              <h3>Sobre este producto</h3>
              <p class="detalle-descripcion-texto">{{ producto.descripcion }}</p>
              <div v-if="producto.especificaciones_tecnicas" class="detalle-descripcion-destacado">
                <i class="ri-information-line"></i>
                <div>
                  <strong>Especificaciones técnicas</strong>
                  <p>{{ producto.especificaciones_tecnicas }}</p>
                </div>
              </div>
            </div>

            <!-- ESPECIFICACIONES -->
            <table v-else-if="tabActiva === 'especificaciones'" class="specs-table">
              <tbody>
                <tr v-for="row in especificacionesCompletas" :key="row.label">
                  <td>{{ row.label }}</td>
                  <td>{{ row.value }}</td>
                </tr>
              </tbody>
            </table>

            <!-- RESEÑAS -->
            <div v-else-if="tabActiva === 'resenas'" class="detalle-resenas-layout">
              <div>
                <div v-if="totalResenas" class="review-summary">
                  <div class="review-summary-avg">
                    <strong>{{ promedioResenas.toFixed(1) }}</strong>
                    <span class="detalle-rating-stars"><i v-for="n in 5" :key="n" :class="n <= Math.round(promedioResenas) ? 'ri-star-fill' : 'ri-star-line'"></i></span>
                    <span>{{ totalResenas }} reseña{{ totalResenas === 1 ? '' : 's' }}</span>
                  </div>
                  <div class="review-summary-bars">
                    <div v-for="n in [5,4,3,2,1]" :key="n" class="review-summary-bar-row">
                      <span>{{ n }} <i class="ri-star-fill"></i></span>
                      <div class="review-summary-bar"><span :style="{ width: (totalResenas ? (distribucionResenas[n] / totalResenas) * 100 : 0) + '%' }"></span></div>
                      <span class="review-summary-bar-count">{{ distribucionResenas[n] }}</span>
                    </div>
                  </div>
                </div>

                <div v-if="!misResenas.length" class="text-center text-muted" style="padding:30px 0;">
                  Aún no hay reseñas para este producto. ¡Sé el primero en opinar!
                </div>
                <div v-for="r in misResenas" :key="r.id_resena" class="review-card">
                  <div class="review-card-header">
                    <div class="review-card-author">
                      <div class="review-card-avatar">{{ inicialesUsuario(r.usuario) }}</div>
                      <div>
                        <div class="review-card-name">{{ nombreUsuario(r.usuario) }}</div>
                        <div class="review-card-date">{{ new Date(r.fecha).toLocaleDateString('es-CO', { day:'numeric', month:'long', year:'numeric' }) }}</div>
                      </div>
                    </div>
                    <span class="detalle-rating-stars small"><i v-for="n in 5" :key="n" :class="n <= r.calificacion ? 'ri-star-fill' : 'ri-star-line'"></i></span>
                  </div>
                  <p class="review-card-text">{{ r.comentario }}</p>
                </div>
              </div>

              <div>
                <h3 style="font-family:var(--font-main);font-weight:700;margin-bottom:16px;">Escribir una reseña</h3>

                <div v-if="!auth.isAuthenticated" class="review-form-card text-center">
                  <p class="text-muted mb-16" style="font-size:0.88rem;">Inicia sesión para compartir tu experiencia con este producto.</p>
                  <button class="btn btn-primary btn-block" @click="abrirFormularioResena"><i class="ri-login-box-line"></i> Iniciar Sesión</button>
                </div>
                <div v-else-if="yaReseno" class="review-form-card text-center">
                  <i class="ri-checkbox-circle-line" style="font-size:1.6rem;color:var(--success);"></i>
                  <p class="text-muted mt-10" style="font-size:0.88rem;">Ya escribiste una reseña para este producto. ¡Gracias por tu opinión!</p>
                </div>
                <form v-else class="review-form-card" @submit.prevent="publicarResena">
                  <div class="form-group">
                    <label class="form-label">Tu calificación</label>
                    <div class="review-stars-input">
                      <i
                        v-for="n in 5"
                        :key="n"
                        :class="n <= nuevaResena.calificacion ? 'ri-star-fill' : 'ri-star-line'"
                        class="star-input"
                        @click="nuevaResena.calificacion = n"
                      ></i>
                    </div>
                  </div>
                  <div class="form-group">
                    <label class="form-label required">Comentario</label>
                    <textarea v-model="nuevaResena.comentario" class="form-control" rows="4" placeholder="Comparte tu experiencia con este producto..." required></textarea>
                  </div>
                  <button type="submit" class="btn btn-primary btn-block" :disabled="enviandoResena">
                    <i class="ri-send-plane-line"></i> {{ enviandoResena ? 'Publicando...' : 'Publicar reseña' }}
                  </button>
                </form>
              </div>
            </div>

            <!-- PREGUNTAS FRECUENTES -->
            <div v-else style="max-width:720px;">
              <div v-for="(f, i) in preguntasFrecuentes" :key="f.pregunta" class="faq-item">
                <div class="faq-question" :class="{ active: faqAbierta === i }" @click="toggleFaq(i)">
                  <span>{{ f.pregunta }}</span>
                  <i class="ri-arrow-down-s-line faq-chevron"></i>
                </div>
                <div class="faq-answer" :class="{ open: faqAbierta === i }">
                  <div class="faq-answer-inner">{{ f.respuesta }}</div>
                </div>
              </div>
            </div>
          </div>
        </div>

        <div v-if="serviciosSugeridos.length" class="detalle-servicio-cta" style="margin-top:48px;">
          <div class="detalle-servicio-cta-icon"><i class="ri-tools-fill"></i></div>
          <div class="detalle-servicio-cta-body">
            <h3>¿Prefieres que lo instalemos por ti?</h3>
            <p>Cotiza el servicio con nuestro equipo y nosotros nos encargamos de aplicar o instalar este producto.</p>
            <div class="detalle-servicio-cta-list">
              <RouterLink v-for="s in serviciosSugeridos" :key="s.id_servicio" :to="`/servicios/${s.id_servicio}`" class="detalle-servicio-chip">
                <img :src="s.imagen_url" :alt="s.nombre_servicio" />
                <span>{{ s.nombre_servicio }}</span>
              </RouterLink>
            </div>
          </div>
          <button class="btn btn-primary btn-lg" @click="irACotizar"><i class="ri-file-list-line"></i> Cotizar Servicio</button>
        </div>

        <div v-if="relacionados.length" class="mb-24" style="margin-top:48px;">
          <h2 class="section-title" style="text-align:left;">Productos Relacionados</h2>
          <div class="products-grid">
            <ProductCard v-for="p in relacionados" :key="p.id_producto" :producto="p" />
          </div>
        </div>
      </div>
    </section>

    <CalculadoraPinturaModal :producto="producto" :mostrar="mostrarCalculadora" @cerrar="mostrarCalculadora = false" />
    <SelectorColorModal
      :mostrar="mostrarSelectorColor"
      :color-inicial="producto.color"
      @cerrar="mostrarSelectorColor = false"
      @color-confirmado="onColorConfirmado"
    />
    <CotizarLoginModal
      :mostrar="mostrarModalLoginResena"
      icono="ri-star-line"
      titulo="Inicia sesión para escribir una reseña"
      texto="Necesitas una cuenta para compartir tu opinión sobre este producto."
      @cerrar="mostrarModalLoginResena = false"
      @iniciar-sesion="irALoginResena"
    />
    <CotizarLoginModal :mostrar="mostrarModalLoginCotizar" @cerrar="mostrarModalLoginCotizar = false" @iniciar-sesion="irALoginCotizar" />
  </template>

  <div v-else class="container" style="padding:80px 0;text-align:center;">
    <p>No se encontró el producto.</p>
    <RouterLink to="/productos" class="btn btn-primary" style="margin-top:16px;">Volver al catálogo</RouterLink>
  </div>
</template>

<style scoped>
.detalle-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 48px; padding: 40px 0; }
.detalle-galeria-principal { position: relative; border-radius: var(--radius); overflow: hidden; border: 1px solid var(--border); aspect-ratio: 1; }
.detalle-galeria-principal img { width: 100%; height: 100%; object-fit: cover; transition: transform 0.3s ease; }
/* Variantes de tamaño: el envase completo dentro de un marco blanco, para que al cambiar de
   tamaño se vea entero y solo crezca/encoja — no recortado como con object-fit: cover. */
.detalle-galeria-principal img.es-variante-tamano { object-fit: contain; padding: 10%; background: #fff; }
.detalle-oferta-badge { position: absolute; top: 16px; left: 16px; }
.detalle-galeria-thumbs { display: flex; gap: 12px; margin-top: 12px; }
.detalle-thumb { width: 72px; height: 72px; border-radius: var(--radius-sm); overflow: hidden; border: 2px solid var(--border); padding: 0; cursor: pointer; }
.detalle-thumb.active { border-color: var(--primary); }
.detalle-thumb img { width: 100%; height: 100%; object-fit: cover; }

.detalle-color-swatch { display: inline-block; width: 12px; height: 12px; border-radius: 50%; border: 1px solid rgba(0,0,0,0.15); margin-right: 6px; vertical-align: middle; }

.detalle-share-bar { display: flex; align-items: center; gap: 10px; margin-top: 16px; padding: 14px; background: white; border-radius: var(--radius); border: 1px solid var(--border); }
.detalle-share-bar span { font-size: 0.8rem; font-family: var(--font-main); font-weight: 600; color: var(--text-muted); }
.detalle-share-btn { width: 34px; height: 34px; border-radius: 50%; display: flex; align-items: center; justify-content: center; color: white; font-size: 1rem; flex-shrink: 0; }
.detalle-share-btn.whatsapp { background: #25d366; }
.detalle-share-btn.facebook { background: #1877f2; }

.detalle-titulo { font-family: var(--font-main); font-weight: 800; font-size: 1.8rem; color: var(--secondary); margin: 8px 0 10px; }

.detalle-rating { display: flex; align-items: center; gap: 10px; margin-bottom: 18px; cursor: pointer; width: fit-content; }
.detalle-rating-stars { color: var(--accent); font-size: 1rem; display: inline-flex; gap: 2px; }
.detalle-rating-stars.small { font-size: 0.85rem; }
.detalle-rating-count { font-size: 0.82rem; color: var(--text-muted); }
.detalle-rating:hover .detalle-rating-count { color: var(--primary); text-decoration: underline; }

.detalle-precio-box { display: flex; align-items: baseline; gap: 12px; margin-bottom: 20px; }
.detalle-precio { font-family: var(--font-main); font-weight: 800; font-size: 2rem; color: var(--primary); }

.combo-panel { background: var(--off-white); border: 1px solid var(--border); border-radius: var(--radius); padding: 18px; margin-bottom: 20px; }
.combo-panel-header { display: flex; gap: 12px; margin-bottom: 14px; }
.combo-panel-header i { font-size: 1.3rem; color: var(--primary); flex-shrink: 0; margin-top: 2px; }
.combo-panel-header strong { display: block; font-family: var(--font-main); font-weight: 800; color: var(--secondary); margin-bottom: 3px; }
.combo-panel-header p { font-size: 0.84rem; color: var(--text-light); line-height: 1.4; }
.combo-panel-items { display: flex; gap: 10px; flex-wrap: wrap; margin-bottom: 14px; }
.combo-panel-item { display: flex; align-items: center; gap: 8px; background: white; border: 1.5px solid var(--border); border-radius: 20px; padding: 5px 14px 5px 5px; font-size: 0.8rem; font-weight: 600; color: var(--secondary); transition: var(--transition); }
.combo-panel-item:hover { border-color: var(--primary); color: var(--primary); }
.combo-panel-item.activo { border-color: var(--primary); background: rgba(192,57,43,0.06); }
.combo-panel-item img { width: 28px; height: 28px; border-radius: 50%; object-fit: cover; }
.combo-panel-price { display: flex; align-items: baseline; gap: 12px; flex-wrap: wrap; padding-top: 14px; border-top: 1px dashed var(--border); }
.combo-panel-total { font-size: 0.9rem; color: var(--text-light); }
.combo-panel-total strong { font-family: var(--font-main); font-weight: 800; font-size: 1.3rem; color: var(--primary); margin-right: 4px; }
.combo-panel-ahorro { font-size: 0.78rem; font-weight: 700; color: var(--success); background: rgba(39,174,96,0.12); padding: 3px 10px; border-radius: 20px; }

.detalle-attrs-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; margin: 20px 0; }
.detalle-attr { background: var(--off-white); border-radius: var(--radius-sm); padding: 10px 14px; }
.detalle-attr-label { font-size: 0.68rem; font-family: var(--font-main); font-weight: 700; text-transform: uppercase; letter-spacing: 0.5px; color: var(--text-muted); margin-bottom: 3px; display: flex; align-items: center; gap: 5px; }
.detalle-attr-label i { color: var(--primary); }
.detalle-attr-value { font-size: 0.9rem; font-weight: 600; color: var(--secondary); }
.detalle-attr-colores { grid-column: 1 / -1; }
.detalle-attr-colores-list { display: flex; flex-wrap: wrap; gap: 6px 12px; }
.detalle-color-pill { display: inline-flex; align-items: center; font-size: 0.85rem; font-weight: 600; color: var(--secondary); }
.detalle-color-pill .detalle-color-swatch { width: 14px; height: 14px; }

.detalle-attr-color { width: 100%; text-align: left; cursor: pointer; transition: var(--transition); border: 1.5px solid transparent; }
.detalle-attr-color:hover { border-color: var(--primary); background: rgba(192,57,43,0.04); }
.detalle-attr-color-value { display: flex; align-items: center; gap: 6px; }
.detalle-attr-color-value span:nth-child(2) { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.detalle-attr-color-value i { color: var(--text-muted); font-size: 1.1rem; flex-shrink: 0; }
.detalle-attr-color-swatch { width: 14px; height: 14px; border-radius: 50%; border: 1.5px solid var(--border); flex-shrink: 0; }

.detalle-stock-box { display: flex; align-items: center; gap: 10px; padding: 14px; border-radius: var(--radius-sm); margin-bottom: 4px; }
.detalle-stock-box.stock-in { background: rgba(39,174,96,0.08); }
.detalle-stock-box.stock-low { background: rgba(243,156,18,0.08); }
.detalle-stock-box.stock-out { background: rgba(192,57,43,0.08); }
.detalle-stock-dot { width: 8px; height: 8px; border-radius: 50%; flex-shrink: 0; }
.stock-in .detalle-stock-dot { background: var(--success); }
.stock-low .detalle-stock-dot { background: var(--warning); animation: detalle-pulse 1.5s infinite; }
.stock-out .detalle-stock-dot { background: var(--danger); }
@keyframes detalle-pulse { 0%, 100% { opacity: 1; } 50% { opacity: 0.3; } }
.detalle-stock-text { font-size: 0.85rem; font-weight: 600; }
.stock-in .detalle-stock-text { color: var(--success); }
.stock-low .detalle-stock-text { color: var(--warning); }
.stock-out .detalle-stock-text { color: var(--danger); }
.detalle-stock-hint { font-size: 0.75rem; color: var(--warning); margin-top: 2px; }

.detalle-trust { display: flex; flex-direction: column; gap: 10px; margin-top: 20px; font-size: 0.82rem; color: var(--text-light); }
.detalle-trust i { color: var(--primary); margin-right: 4px; }

.detalle-tabs { border-top: 1px solid var(--border); padding-top: 8px; margin-top: 24px; }
.detalle-tabs-nav { display: flex; gap: 4px; border-bottom: 2px solid var(--border); overflow-x: auto; }
.detalle-tabs-nav button { padding: 14px 22px; font-family: var(--font-main); font-weight: 600; font-size: 0.88rem; color: var(--text-muted); border-bottom: 2px solid transparent; margin-bottom: -2px; white-space: nowrap; transition: var(--transition); }
.detalle-tabs-nav button:hover { color: var(--text); }
.detalle-tabs-nav button.active { color: var(--primary); border-color: var(--primary); }
.detalle-tabs-body { padding: 28px 0; color: var(--text-light); line-height: 1.8; }

.detalle-descripcion { max-width: 760px; }
.detalle-descripcion h3 { font-family: var(--font-main); font-weight: 700; color: var(--secondary); margin-bottom: 14px; font-size: 1.05rem; }
.detalle-descripcion-texto { font-size: 1rem; color: var(--text-light); line-height: 1.85; margin-bottom: 22px; }
.detalle-descripcion-destacado { display: flex; gap: 12px; background: var(--off-white); border-radius: var(--radius-sm); padding: 20px; border-left: 4px solid var(--primary); }
.detalle-descripcion-destacado i { color: var(--primary); font-size: 1.2rem; flex-shrink: 0; margin-top: 2px; }
.detalle-descripcion-destacado strong { display: block; font-family: var(--font-main); font-weight: 700; font-size: 0.9rem; color: var(--secondary); margin-bottom: 6px; }
.detalle-descripcion-destacado p { font-size: 0.9rem; line-height: 1.7; margin: 0; }

.specs-table { width: 100%; border-collapse: collapse; background: white; border-radius: var(--radius); overflow: hidden; border: 1px solid var(--border); }
.specs-table tr { border-bottom: 1px solid var(--border); }
.specs-table tr:last-child { border-bottom: none; }
.specs-table td { padding: 12px 16px; font-size: 0.9rem; }
.specs-table td:first-child { font-family: var(--font-main); font-weight: 700; color: var(--text-muted); font-size: 0.8rem; text-transform: uppercase; letter-spacing: 0.5px; width: 38%; background: var(--off-white); }
.specs-table td:last-child { color: var(--text); }

.detalle-resenas-layout { display: grid; grid-template-columns: 1fr 1fr; gap: 40px; align-items: start; }
.review-summary { display: flex; gap: 32px; align-items: center; background: var(--off-white); border-radius: var(--radius); padding: 24px; margin-bottom: 24px; flex-wrap: wrap; }
.review-summary-avg { display: flex; flex-direction: column; align-items: center; gap: 4px; flex-shrink: 0; }
.review-summary-avg strong { font-family: var(--font-main); font-weight: 900; font-size: 2.2rem; color: var(--secondary); line-height: 1; }
.review-summary-avg span:last-child { font-size: 0.8rem; color: var(--text-muted); }
.review-summary-bars { flex: 1; min-width: 160px; display: flex; flex-direction: column; gap: 5px; }
.review-summary-bar-row { display: flex; align-items: center; gap: 8px; font-size: 0.75rem; color: var(--text-muted); }
.review-summary-bar-row i { color: var(--accent); font-size: 0.7rem; }
.review-summary-bar { flex: 1; height: 6px; background: var(--border); border-radius: 4px; overflow: hidden; }
.review-summary-bar span { display: block; height: 100%; background: var(--accent); }
.review-summary-bar-count { width: 16px; text-align: right; }

.review-card { background: var(--off-white); border-radius: var(--radius); padding: 20px; margin-bottom: 16px; }
.review-card-header { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 10px; }
.review-card-author { display: flex; align-items: center; gap: 10px; }
.review-card-avatar { width: 36px; height: 36px; border-radius: 50%; background: var(--primary); color: white; display: flex; align-items: center; justify-content: center; font-family: var(--font-main); font-weight: 700; font-size: 0.82rem; flex-shrink: 0; }
.review-card-name { font-family: var(--font-main); font-weight: 700; font-size: 0.87rem; color: var(--secondary); }
.review-card-date { font-size: 0.72rem; color: var(--text-muted); }
.review-card-text { font-size: 0.87rem; color: var(--text-light); line-height: 1.6; margin: 0; }

.review-form-card { background: white; border-radius: var(--radius); padding: 24px; border: 1px solid var(--border); }
.review-stars-input { display: flex; gap: 6px; }
.star-input { font-size: 1.6rem; color: var(--border); cursor: pointer; transition: var(--transition); }
.star-input.ri-star-fill { color: var(--accent); }

@media (max-width: 992px) {
  .detalle-resenas-layout { grid-template-columns: 1fr; }
}
@media (max-width: 768px) {
  .detalle-grid { grid-template-columns: 1fr; gap: 24px; }
  .detalle-attrs-grid { grid-template-columns: 1fr; }
}
</style>
