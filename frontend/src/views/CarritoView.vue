<script setup>
// RF08/RF09 - carrito: ítems, cantidades, resumen de compra, proceder al pago
import { ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import { Swiper, SwiperSlide } from 'swiper/vue'
import { Navigation, Pagination } from 'swiper/modules'
import 'swiper/css'
import 'swiper/css/navigation'
import 'swiper/css/pagination'
import { useCartStore } from '../stores/cart'
import { useCatalogStore } from '../stores/catalog'
import { useAuthStore } from '../stores/auth'
import { formatCOP } from '../composables/useFormat'
import { useCotizarGate } from '../composables/useCotizarGate'
import ProductCard from '../components/product/ProductCard.vue'
import CotizarLoginModal from '../components/service/CotizarLoginModal.vue'

const cart = useCartStore()
const catalog = useCatalogStore()
const auth = useAuthStore()
const router = useRouter()
const { mostrarModalLogin: mostrarModalLoginCotizar, irACotizar, irALogin: irALoginCotizar } = useCotizarGate()

// Misma regla que CheckoutView: el sitio cobra solo los productos. El envío nunca se suma al total,
// porque lo paga el cliente directo a la transportadora al recibir el pedido (o es gratis si recoge
// en la tienda o si la compra supera $400.000). Antes el carrito sumaba $8.000 al total y no
// coincidía con lo que después mostraba el pago.
const envioGratis = computed(() => cart.total >= 400000)

// ¿Nuestro equipo puede aplicar/instalar lo que llevas en el carrito?
const serviciosSugeridos = computed(() => {
  if (!cart.items.length) return []
  const categorias = cart.items.map((item) => item.id_categoria)
  return catalog.getServiciosSugeridos(categorias).slice(0, 3)
})

// Carrito vacío: 3 carruseles de productos (solo productos — un servicio no se puede "agregar",
// así que mezclarlo aquí generaría el mismo enredo que ya evitamos en Nueva Cotización) más un
// banner aparte para quien busca un servicio, no un producto.
const destacadosSlider = computed(() => catalog.productosDestacados)
const promocionSlider = computed(() => catalog.productosCatalogo.filter((p) => catalog.getActivePromoForProduct(p.id_producto)))
const pinturasSlider = computed(() => catalog.productosCatalogo.filter((p) => catalog.getCategoryName(p.id_categoria) === 'Pinturas y Vinilos'))
const serviciosDestacados = computed(() => catalog.serviciosCatalogo.filter((s) => s.destacado).slice(0, 4))

const sliderBreakpoints = {
  640: { slidesPerView: 2, slidesPerGroup: 2, spaceBetween: 18 },
  1024: { slidesPerView: 3, slidesPerGroup: 3, spaceBetween: 20 },
  1280: { slidesPerView: 4, slidesPerGroup: 4, spaceBetween: 20 },
}

const mostrarModalLogin = ref(false)
function irACheckout() {
  if (!auth.isAuthenticated) {
    mostrarModalLogin.value = true
    return
  }
  router.push('/checkout')
}
function irALogin() {
  mostrarModalLogin.value = false
  router.push({ path: '/login', query: { redirect: '/checkout' } })
}
</script>

<template>
  <section class="services-hero carrito-hero">
    <div class="services-hero-bg">
      <img src="https://images.pexels.com/photos/1669799/pexels-photo-1669799.jpeg?w=1600&q=80" alt="Mi Carrito Acabados 1A" />
    </div>
    <div class="container">
      <div class="breadcrumb">
        <span class="breadcrumb-item"><RouterLink to="/"><i class="ri-home-4-line"></i> Inicio</RouterLink></span>
        <span class="breadcrumb-sep"><i class="ri-arrow-right-s-line"></i></span>
        <span class="breadcrumb-item active">Mi Carrito</span>
      </div>
      <h1 class="services-hero-title">Mi <em>Carrito</em></h1>
      <p class="services-hero-desc">{{ cart.cantidadTotal }} producto(s) en tu carrito — revisa, ajusta cantidades y sigue con tu compra cuando quieras.</p>
    </div>
  </section>

  <section class="section">
    <div class="container">
      <template v-if="cart.items.length">
        <div class="checkout-layout">
          <div class="checkout-panel">
            <div v-for="item in cart.items" :key="item.id_producto" class="cart-item">
              <div class="cart-item-img"><img :src="item.imagen_url" :alt="item.nombre" /></div>
              <div class="cart-item-info">
                <div class="cart-item-name">{{ item.nombre }}</div>
                <div class="cart-item-cat">{{ catalog.getCategoryName(item.id_categoria) }}</div>
                <div class="qty-control">
                  <button class="qty-btn" @click="cart.actualizarCantidad(item.id_producto, Math.max(1, item.cantidad - 1))">-</button>
                  <input
                    class="qty-input"
                    type="number"
                    min="1"
                    :value="item.cantidad"
                    @change="cart.actualizarCantidad(item.id_producto, Math.max(1, Number($event.target.value)))"
                  />
                  <button class="qty-btn" @click="cart.actualizarCantidad(item.id_producto, item.cantidad + 1)">+</button>
                </div>
              </div>
              <div class="cart-item-price">{{ formatCOP(item.precio_venta * item.cantidad) }}</div>
              <i class="ri-delete-bin-line cart-item-remove" @click="cart.eliminarProducto(item.id_producto)"></i>
            </div>

            <div class="d-flex justify-between mt-20">
              <RouterLink to="/productos" class="btn btn-outline-red btn-sm">
                <i class="ri-arrow-left-line"></i> Seguir comprando
              </RouterLink>
              <button class="btn btn-outline-red btn-sm" @click="cart.vaciarCarrito">Vaciar carrito</button>
            </div>

            <div v-if="serviciosSugeridos.length" class="detalle-servicio-cta" style="margin-top:24px;">
              <div class="detalle-servicio-cta-icon"><i class="ri-tools-fill"></i></div>
              <div class="detalle-servicio-cta-body">
                <h3>¿Quieres que lo instalemos por ti?</h3>
                <p>Antes de pagar, puedes cotizar el servicio para que nuestro equipo aplique o instale lo que llevas.</p>
                <div class="detalle-servicio-cta-list">
                  <RouterLink v-for="s in serviciosSugeridos" :key="s.id_servicio" :to="`/servicios/${s.id_servicio}`" class="detalle-servicio-chip">
                    <img :src="s.imagen_url" :alt="s.nombre_servicio" />
                    <span>{{ s.nombre_servicio }}</span>
                  </RouterLink>
                </div>
              </div>
              <button class="btn btn-primary btn-lg" @click="irACotizar"><i class="ri-file-list-line"></i> Cotizar Servicio</button>
            </div>
          </div>

          <aside class="order-summary carrito-resumen">
            <h3 class="carrito-resumen-title">Resumen del pedido</h3>
            <div class="carrito-resumen-row"><span>Subtotal</span><span>{{ formatCOP(cart.total) }}</span></div>
            <div class="carrito-resumen-row">
              <span>Envío</span>
              <span>{{ envioGratis ? 'Gratis' : 'Al recibir' }}</span>
            </div>
            <p class="carrito-resumen-hint">
              <i class="ri-truck-line"></i>
              <template v-if="envioGratis">Tu compra supera $400.000: el envío es gratis.</template>
              <template v-else>El envío se paga aparte, directo a la transportadora al recibir el pedido. Es gratis si recoges en la tienda o si tu compra supera $400.000.</template>
            </p>
            <div class="carrito-resumen-total"><span>Total</span><span class="value">{{ formatCOP(cart.total) }}</span></div>
            <button class="btn btn-primary btn-lg btn-block" @click="irACheckout">
              <i class="ri-lock-line"></i> Proceder al Pago
            </button>
          </aside>
        </div>
      </template>

      <div v-else class="text-center" style="padding:60px 0;">
        <div style="width:96px;height:96px;border-radius:50%;background:var(--light);display:flex;align-items:center;justify-content:center;margin:0 auto 24px;">
          <i class="ri-shopping-cart-2-line" style="font-size:2.5rem;color:var(--text-muted);"></i>
        </div>
        <h2 class="section-title">Aún no hay nada aquí</h2>
        <p class="section-subtitle mb-24">Explora nuestro catálogo de pinturas, materiales de acabado y herramientas para transformar tu próximo proyecto.</p>
        <div class="d-flex gap-16" style="justify-content:center;">
          <RouterLink to="/productos" class="btn btn-primary"><i class="ri-store-line"></i> Ver Catálogo</RouterLink>
        </div>

        <div v-if="destacadosSlider.length" class="carrito-sugeridos">
          <h3 class="section-title" style="text-align:left;font-size:1.3rem;">Destacados</h3>
          <Swiper
            class="carrito-productos-swiper"
            :slides-per-view="1"
            :slides-per-group="1"
            :space-between="18"
            grab-cursor
            :simulate-touch="true"
            :navigation="true"
            :pagination="{ clickable: true }"
            :modules="[Navigation, Pagination]"
            :breakpoints="sliderBreakpoints"
          >
            <SwiperSlide v-for="p in destacadosSlider" :key="'d' + p.id_producto">
              <ProductCard :producto="p" />
            </SwiperSlide>
          </Swiper>
        </div>

        <div v-if="promocionSlider.length" class="carrito-sugeridos">
          <h3 class="section-title" style="text-align:left;font-size:1.3rem;">En Promoción</h3>
          <Swiper
            class="carrito-productos-swiper"
            :slides-per-view="1"
            :slides-per-group="1"
            :space-between="18"
            grab-cursor
            :simulate-touch="true"
            :navigation="true"
            :pagination="{ clickable: true }"
            :modules="[Navigation, Pagination]"
            :breakpoints="sliderBreakpoints"
          >
            <SwiperSlide v-for="p in promocionSlider" :key="'promo' + p.id_producto">
              <ProductCard :producto="p" />
            </SwiperSlide>
          </Swiper>
        </div>

        <div v-if="pinturasSlider.length" class="carrito-sugeridos">
          <h3 class="section-title" style="text-align:left;font-size:1.3rem;">Pinturas y Vinilos</h3>
          <Swiper
            class="carrito-productos-swiper"
            :slides-per-view="1"
            :slides-per-group="1"
            :space-between="18"
            grab-cursor
            :simulate-touch="true"
            :navigation="true"
            :pagination="{ clickable: true }"
            :modules="[Navigation, Pagination]"
            :breakpoints="sliderBreakpoints"
          >
            <SwiperSlide v-for="p in pinturasSlider" :key="'pv' + p.id_producto">
              <ProductCard :producto="p" />
            </SwiperSlide>
          </Swiper>
        </div>

        <!-- Banner de servicios: aparte de los sliders de producto a propósito — un servicio no se
             "agrega al carrito", se cotiza, así que no lo mezclamos con las tarjetas de "Agregar". -->
        <div v-if="serviciosDestacados.length" class="detalle-servicio-cta" style="margin-top:48px;text-align:left;">
          <div class="detalle-servicio-cta-icon"><i class="ri-tools-fill"></i></div>
          <div class="detalle-servicio-cta-body">
            <h3>¿Buscas que te ayudemos a instalarlo?</h3>
            <p>No se agregan al carrito — nuestro equipo te prepara una cotización a la medida de tu proyecto.</p>
            <div class="detalle-servicio-cta-list">
              <RouterLink v-for="s in serviciosDestacados" :key="s.id_servicio" :to="`/servicios/${s.id_servicio}`" class="detalle-servicio-chip">
                <img :src="s.imagen_url" :alt="s.nombre_servicio" />
                <span>{{ s.nombre_servicio }}</span>
              </RouterLink>
            </div>
          </div>
          <RouterLink to="/servicios" class="btn btn-primary btn-lg"><i class="ri-file-list-line"></i> Cotizar Servicios</RouterLink>
        </div>
      </div>
    </div>
  </section>

  <!-- MODAL: requiere sesión para pagar -->
  <Transition name="confirm-modal-fade">
    <div v-if="mostrarModalLogin" class="confirm-modal-overlay" @click.self="mostrarModalLogin = false">
      <Transition name="confirm-modal-pop" appear>
        <div class="confirm-modal">
          <button class="confirm-modal-close" aria-label="Cerrar" @click="mostrarModalLogin = false">
            <i class="ri-close-line"></i>
          </button>
          <div class="confirm-modal-icon"><i class="ri-lock-2-line"></i></div>
          <h3 class="confirm-modal-title">Inicia sesión para continuar</h3>
          <p class="confirm-modal-text">
            Necesitas una cuenta para completar tu compra de forma segura y hacer seguimiento a tu pedido.
          </p>
          <div class="confirm-modal-actions">
            <button class="btn btn-primary btn-lg btn-block" @click="irALogin">
              <i class="ri-login-box-line"></i> Iniciar Sesión
            </button>
            <button class="btn btn-outline-red btn-block" @click="mostrarModalLogin = false">Cancelar</button>
          </div>
          <p class="confirm-modal-footer">¿No tienes cuenta? <RouterLink to="/registro" @click="mostrarModalLogin = false">Regístrate gratis</RouterLink></p>
        </div>
      </Transition>
    </div>
  </Transition>

  <CotizarLoginModal :mostrar="mostrarModalLoginCotizar" @cerrar="mostrarModalLoginCotizar = false" @iniciar-sesion="irALoginCotizar" />
</template>
