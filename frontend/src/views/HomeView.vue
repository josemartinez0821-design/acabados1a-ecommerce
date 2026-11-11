<script setup>
// RF22 - página principal (promociones del mes), replicando github-frontend/index.html sobre el sistema de diseño portado
import { ref, computed, onMounted, reactive } from 'vue'
import { Swiper, SwiperSlide } from 'swiper/vue'
import { Navigation, Pagination, Autoplay } from 'swiper/modules'
import 'swiper/css'
import 'swiper/css/navigation'
import 'swiper/css/pagination'

import { useCatalogStore } from '../stores/catalog'
import { useCartStore } from '../stores/cart'
import { useAuthStore } from '../stores/auth'
import { useToast } from '../composables/useToast'
import { formatCOP } from '../composables/useFormat'

import heroFachada from '../assets/hero-fachada.jpg'
import heroInterior from '../assets/hero-interior.jpg'
import heroPremium from '../assets/hero-premium.jpg'
import heroHerramientas from '../assets/hero-herramientas.jpg'

const catalog = useCatalogStore()
const cart = useCartStore()
const auth = useAuthStore()
const { showToast } = useToast()

const heroSlides = [
  { img: heroFachada, icon: 'ri-paint-bucket-line', caption: 'Pinturas de alta calidad' },
  { img: heroPremium, icon: 'ri-brush-line', caption: 'Acabados premium' },
  { img: heroInterior, icon: 'ri-home-gear-line', caption: 'Instalación profesional' },
  { img: heroHerramientas, icon: 'ri-tools-line', caption: 'Todo en herramientas' },
]

const bannerItems = [
  { icon: 'ri-truck-line', text: 'Envío a domicilio' },
  { icon: 'ri-price-tag-3-line', text: 'Precios competitivos' },
  { icon: 'ri-customer-service-line', text: 'Asesoría personalizada' },
  { icon: 'ri-shield-check-line', text: 'Garantía de calidad' },
  { icon: 'ri-bank-card-line', text: 'Múltiples métodos de pago' },
  { icon: 'ri-paint-brush-line', text: 'Pinturas certificadas' },
  { icon: 'ri-tools-line', text: 'Servicio profesional' },
  { icon: 'ri-map-pin-line', text: 'Tesalia, Huila' },
]

const serviceStrip = [
  { icon: 'ri-truck-line', title: 'Envío a Domicilio', desc: 'Tesalia y municipios cercanos' },
  { icon: 'ri-refund-line', title: 'Garantía 30 días', desc: 'En todos los productos' },
  { icon: 'ri-bank-card-line', title: 'Pago Seguro', desc: 'Tarjeta, Nequi, transferencia' },
  { icon: 'ri-headphone-line', title: 'Soporte 24/7', desc: 'Lun-Sáb 8am–6pm' },
]

const whyUs = [
  { icon: 'ri-medal-line', title: 'Calidad Certificada', desc: 'Trabajamos únicamente con marcas reconocidas como Pintuco, Sherwin-Williams y Corona, garantizando la mejor calidad.' },
  { icon: 'ri-user-star-line', title: 'Asesoría Experta', desc: 'Nuestro equipo de especialistas te guía para elegir el producto correcto según tu proyecto y presupuesto.' },
  { icon: 'ri-truck-line', title: 'Entrega Rápida', desc: 'Hacemos entregas en Tesalia y municipios cercanos, con tiempos de entrega de 24-48h.' },
  { icon: 'ri-price-tag-3-line', title: 'Precios Justos', desc: 'Ofrecemos precios competitivos y descuentos especiales para proyectos al por mayor y clientes frecuentes.' },
]

const aboutItems = [
  'Pinturas y Vinilos tipo 1, 2 y 3',
  'Instalación Drywall y PVC',
  'Estuco plástico y Graniplast',
  'Pegacol y materiales adhesivos',
  'Herramientas y accesorios',
  'Asesoría en diseño de interiores',
]

const testimonials = [
  { stars: 5, text: '"Excelente servicio y productos de primera calidad. El equipo de Acabados 1A me asesoró perfectamente para elegir los colores de mi apartamento. Totalmente recomendados."', initials: 'CA', name: 'Carlos Andrés Gómez', role: 'Cliente — Pitalito, Huila' },
  { stars: 5, text: '"Compré pinturas y el servicio de instalación de Drywall. Quedé muy satisfecha con el resultado y con los precios. El trabajo fue impecable y terminaron antes de lo esperado."', initials: 'MF', name: 'María Fernanda López', role: 'Clienta — La Plata, Huila' },
  { stars: 4, text: '"Muy buenos precios en estuco y Graniplast. El proceso de compra online fue sencillo y recibí mi pedido al día siguiente. Seguiré comprando aquí para mis obras."', initials: 'PV', name: 'Pedro Antonio Vargas', role: 'Contratista — Neiva, Huila' },
  { stars: 5, text: '"La asesoría de diseño de interiores fue increíble. Me ayudaron a transformar completamente mi negocio con colores corporativos. 100% recomendados para proyectos empresariales."', initials: 'LR', name: 'Luisa Ramírez', role: 'Empresaria — Garzón, Huila' },
]

const stats = [
  { count: 350, suffix: '+', label: 'Productos' },
  { count: 1200, suffix: '+', label: 'Clientes' },
  { count: 8, suffix: '+', label: 'Años exp.' },
  { count: 100, suffix: '%', label: 'Garantía' },
]
const statValues = reactive(stats.map(() => 0))
const statsEl = ref(null)

function animateCounters() {
  const observer = new IntersectionObserver((entries) => {
    entries.forEach((entry) => {
      if (!entry.isIntersecting) return
      stats.forEach((s, i) => {
        const step = Math.ceil(s.count / 50)
        const timer = setInterval(() => {
          statValues[i] = Math.min(statValues[i] + step, s.count)
          if (statValues[i] >= s.count) clearInterval(timer)
        }, 30)
      })
      observer.disconnect()
    })
  }, { threshold: 0.5 })
  if (statsEl.value) observer.observe(statsEl.value)
}

function agregarProducto(producto) {
  const stock = catalog.getProductStock(producto.id_producto)
  if (stock === 0) {
    showToast('Producto agotado', 'danger')
    return
  }
  cart.agregarProducto(producto)
}

function promoLink(promo) {
  // `productos` es un arreglo (un combo puede traer varios) — el link va al primero.
  if (promo.productos?.length) return `/productos/${promo.productos[0]}`
  if (promo.id_servicio) return `/servicios/${promo.id_servicio}`
  return '/productos'
}

function precioFinal(producto) {
  const promo = catalog.getActivePromoForProduct(producto.id_producto)
  if (!promo) return producto.precio_venta
  return Math.round(producto.precio_venta * (1 - promo.descuento_porcentaje / 100))
}

const contactForm = ref({ nombre: '', telefono: '', email: '', mensaje: '' })
function submitContactForm() {
  showToast('¡Mensaje enviado! Nos pondremos en contacto contigo pronto.', 'success')
  contactForm.value = { nombre: '', telefono: '', email: '', mensaje: '' }
}

const primerNombre = computed(() => auth.usuario?.nombre?.split(' ')[0] || '')

onMounted(animateCounters)
</script>

<template>
  <!-- HERO -->
  <section class="hero">
    <div class="hero-bg-pattern"></div>
    <div class="hero-inner">
      <div class="hero-text">
        <div class="hero-badge">
          <i class="ri-verified-badge-line"></i>
          <span>Líderes en acabados — Tesalia, Huila</span>
        </div>

        <h1 class="hero-title">
          Transforma tu<br>
          espacio con<br>
          <span class="highlight">acabados premium</span>
        </h1>

        <p class="hero-subtitle">
          Pinturas, vinilos, materiales de acabado y servicios de instalación profesional.
          Todo lo que necesitas para darle vida a tus proyectos.
        </p>

        <div class="hero-cta">
          <RouterLink to="/productos" class="btn btn-primary btn-lg">
            <i class="ri-store-line"></i> Ver Catálogo
          </RouterLink>
          <RouterLink to="/cotizaciones" class="btn btn-outline btn-lg">
            <i class="ri-file-list-line"></i> Solicitar una Cotización
          </RouterLink>
        </div>

        <div class="hero-stats" ref="statsEl">
          <div class="stat-item" v-for="(s, i) in stats" :key="s.label">
            <div class="stat-value">{{ statValues[i] }}{{ s.suffix }}</div>
            <div class="stat-label">{{ s.label }}</div>
          </div>
        </div>
      </div>

      <div class="hero-visual">
        <Swiper
          class="hero-swiper"
          :modules="[Navigation, Pagination, Autoplay]"
          loop
          :speed="800"
          grab-cursor
          :autoplay="{ delay: 5000, disableOnInteraction: false, pauseOnMouseEnter: true }"
          navigation
          :pagination="{ clickable: true, dynamicBullets: true }"
        >
          <SwiperSlide v-for="slide in heroSlides" :key="slide.caption">
            <div class="hero-swiper-slide">
              <img :src="slide.img" :alt="slide.caption" />
              <div class="hero-slide-caption">
                <span><i :class="slide.icon"></i>&nbsp; {{ slide.caption }}</span>
              </div>
            </div>
          </SwiperSlide>
        </Swiper>
      </div>
    </div>
  </section>

  <!-- BANNER STRIP -->
  <div class="banner-strip" aria-hidden="true">
    <div class="banner-strip-inner">
      <div class="strip-item" v-for="(item, i) in [...bannerItems, ...bannerItems]" :key="i">
        <i :class="item.icon"></i> {{ item.text }}
      </div>
    </div>
  </div>

  <!-- SERVICE STRIP -->
  <section class="section-sm" style="background:var(--off-white);">
    <div class="container">
      <div class="service-strip">
        <div class="service-strip-item" v-for="item in serviceStrip" :key="item.title">
          <div class="service-strip-icon"><i :class="item.icon"></i></div>
          <div>
            <div class="service-strip-title">{{ item.title }}</div>
            <div class="service-strip-desc">{{ item.desc }}</div>
          </div>
        </div>
      </div>
    </div>
  </section>

  <!-- PROMOCIONES -->
  <section v-if="catalog.promocionesDestacadas.length" class="section" style="background:white;">
    <div class="container">
      <div class="section-header">
        <span class="section-eyebrow">Ofertas especiales</span>
        <h2 class="section-title">Promociones del Mes</h2>
        <p class="section-subtitle">Aprovecha nuestras ofertas exclusivas por tiempo limitado</p>
      </div>

      <Swiper class="promo-swiper" :modules="[Pagination, Autoplay]" loop :speed="700" :autoplay="{ delay: 5000, disableOnInteraction: false }" :slides-per-view="1" :space-between="24" :pagination="{ clickable: true }">
        <SwiperSlide v-for="promo in catalog.promocionesDestacadas" :key="promo.id_promocion">
          <RouterLink :to="promoLink(promo)" class="promo-card">
            <img :src="promo.imagen_url" :alt="promo.titulo" />
            <div class="promo-overlay">
              <div class="promo-content">
                <span class="promo-tag">
                  {{ promo.tipo === 'descuento' ? `${promo.descuento_porcentaje}% OFF` : promo.tipo === 'combo' ? 'Combo Especial' : 'Servicio' }}
                </span>
                <h3 class="promo-title">{{ promo.titulo }}</h3>
                <p class="promo-desc">{{ promo.descripcion }}</p>
              </div>
            </div>
          </RouterLink>
        </SwiperSlide>
      </Swiper>
    </div>
  </section>

  <!-- CATEGORÍAS -->
  <section class="section" style="background:var(--off-white);">
    <div class="container">
      <div class="section-header">
        <span class="section-eyebrow">Explora</span>
        <h2 class="section-title">Categorías de Productos</h2>
        <p class="section-subtitle">Encuentra todo lo que necesitas organizado por categoría</p>
      </div>
      <div class="categories-grid">
        <RouterLink
          v-for="cat in catalog.categorias"
          :key="cat.id_categoria"
          :to="`/productos?cat=${cat.id_categoria}`"
          class="category-card"
        >
          <div class="cat-icon"><i :class="catalog.getCategoryIcon(cat.nombre)"></i></div>
          <div class="cat-name">{{ cat.nombre }}</div>
          <div class="cat-count">{{ catalog.getCategoryCount(cat.id_categoria) }} productos</div>
        </RouterLink>
      </div>
    </div>
  </section>

  <!-- PRODUCTOS DESTACADOS -->
  <section class="section">
    <div class="container">
      <div class="d-flex justify-between align-center mb-32" style="flex-wrap:wrap;gap:16px;">
        <div>
          <span class="section-eyebrow">Lo más vendido</span>
          <h2 class="section-title mb-0">Productos Destacados</h2>
        </div>
        <RouterLink to="/productos" class="btn btn-outline-red">
          Ver todos <i class="ri-arrow-right-line"></i>
        </RouterLink>
      </div>

      <Swiper
        class="featured-products-swiper"
        :slides-per-view="1"
        :slides-per-group="1"
        :space-between="20"
        grab-cursor
        :simulate-touch="true"
        loop
        :autoplay="{ delay: 4000, disableOnInteraction: false, pauseOnMouseEnter: true }"
        :pagination="{ clickable: true }"
        :modules="[Pagination, Autoplay]"
        :breakpoints="{
          640: { slidesPerView: 2, slidesPerGroup: 2, spaceBetween: 20 },
          1024: { slidesPerView: 3, slidesPerGroup: 3, spaceBetween: 24 },
          1280: { slidesPerView: 4, slidesPerGroup: 4, spaceBetween: 24 },
        }"
      >
        <SwiperSlide v-for="p in catalog.productosDestacados" :key="p.id_producto">
          <div class="product-card" :class="{ 'product-card-promo': catalog.getActivePromoForProduct(p.id_producto) }">
            <div class="product-card-img">
              <img :src="p.imagen_url" :alt="p.nombre" loading="lazy" />
              <div v-if="catalog.getActivePromoForProduct(p.id_producto) || catalog.getComboForProduct(p.id_producto)" class="product-card-badges">
                <span v-if="catalog.getActivePromoForProduct(p.id_producto)" class="badge badge-yellow">
                  <i class="ri-price-tag-3-fill"></i> -{{ catalog.getActivePromoForProduct(p.id_producto).descuento_porcentaje }}%
                </span>
                <span v-if="catalog.getComboForProduct(p.id_producto)" class="badge badge-red">
                  <i class="ri-gift-fill"></i> Combo
                </span>
              </div>
              <div class="product-actions-hover">
                <button class="btn btn-primary btn-sm" @click="agregarProducto(p)">
                  <i class="ri-shopping-cart-line"></i> Agregar
                </button>
                <RouterLink :to="`/productos/${p.id_producto}`" class="btn btn-white btn-sm btn-icon">
                  <i class="ri-eye-line"></i>
                </RouterLink>
              </div>
            </div>
            <div class="product-card-body">
              <div class="product-category">{{ catalog.getCategoryName(p.id_categoria) }}</div>
              <h3 class="product-name">{{ p.nombre }}</h3>
              <p class="product-desc">{{ p.descripcion }}</p>
              <div class="d-flex align-center gap-10">
                <span class="product-price">{{ formatCOP(precioFinal(p)) }}</span>
                <span v-if="catalog.getActivePromoForProduct(p.id_producto)" class="product-price-old">{{ formatCOP(p.precio_venta) }}</span>
              </div>
            </div>
            <div class="product-footer">
              <button class="btn btn-primary btn-sm" @click="agregarProducto(p)">
                <i class="ri-shopping-cart-line"></i> Carrito
              </button>
              <RouterLink :to="`/productos/${p.id_producto}`" class="btn btn-outline-red btn-sm">Ver más</RouterLink>
            </div>
          </div>
        </SwiperSlide>
      </Swiper>
    </div>
  </section>

  <!-- SERVICIOS -->
  <section class="section" style="background:var(--off-white);">
    <div class="container">
      <div class="d-flex justify-between align-center mb-32" style="flex-wrap:wrap;gap:16px;">
        <div>
          <span class="section-eyebrow">Profesionales</span>
          <h2 class="section-title mb-0">Nuestros Servicios</h2>
        </div>
        <RouterLink to="/servicios" class="btn btn-outline-red">
          Ver todos <i class="ri-arrow-right-line"></i>
        </RouterLink>
      </div>
      <Swiper
        class="featured-services-swiper"
        :slides-per-view="1"
        :slides-per-group="1"
        :space-between="24"
        grab-cursor
        :simulate-touch="true"
        loop
        :autoplay="{ delay: 4500, disableOnInteraction: false, pauseOnMouseEnter: true }"
        :pagination="{ clickable: true }"
        :modules="[Pagination, Autoplay]"
        :breakpoints="{
          640: { slidesPerView: 2, slidesPerGroup: 2, spaceBetween: 24 },
          1024: { slidesPerView: 3, slidesPerGroup: 3, spaceBetween: 24 },
        }"
      >
        <SwiperSlide v-for="s in catalog.serviciosCatalogo" :key="s.id_servicio">
          <div class="service-card">
            <div class="service-card-img">
              <img :src="s.imagen_url" :alt="s.nombre_servicio" loading="lazy" />
            </div>
            <div class="service-card-body">
              <div class="service-type">{{ s.tipo_servicio.replace('_', ' ') }}</div>
              <h3 class="service-name">{{ s.nombre_servicio }}</h3>
              <p class="service-desc">{{ s.descripcion }}</p>
              <div class="d-flex justify-between align-center">
                <div>
                  <span v-if="s.precio_hora" class="service-price">{{ formatCOP(s.precio_hora) }}/hora</span>
                  <span v-else-if="s.precio_dia" class="service-price">{{ formatCOP(s.precio_dia) }}/día</span>
                  <span v-else-if="s.precio_proyecto" class="service-price">Desde {{ formatCOP(s.precio_proyecto) }}</span>
                </div>
                <RouterLink :to="`/servicios/${s.id_servicio}`" class="btn btn-primary btn-sm">Ver Servicio</RouterLink>
              </div>
            </div>
          </div>
        </SwiperSlide>
      </Swiper>
    </div>
  </section>

  <!-- ¿POR QUÉ ELEGIRNOS? -->
  <section class="section">
    <div class="container">
      <div class="section-header">
        <span class="section-eyebrow">Nuestros valores</span>
        <h2 class="section-title">¿Por qué elegir Acabados 1A?</h2>
        <p class="section-subtitle">Más de 10 años brindando soluciones de calidad a hogares y empresas del Huila</p>
      </div>
      <div class="why-grid">
        <div class="why-card" v-for="w in whyUs" :key="w.title">
          <div class="why-icon"><i :class="w.icon"></i></div>
          <h3 class="why-title">{{ w.title }}</h3>
          <p class="why-desc">{{ w.desc }}</p>
        </div>
      </div>
    </div>
  </section>

  <!-- SOBRE NOSOTROS -->
  <section class="section" style="background:var(--off-white);">
    <div class="container">
      <div class="about-section">
        <div class="about-img-block">
          <img src="https://images.unsplash.com/photo-1562259929-b4e1fd3aef09?w=600&q=80" alt="Acabados y Diseños 1A" />
        </div>
        <div class="about-content">
          <div class="about-tag">
            <i class="ri-store-line"></i>
            <span>Quiénes somos</span>
          </div>
          <h2 class="section-title mb-0" style="text-align:left;">ACABADOS Y DISEÑOS 1A</h2>
          <div style="width:60px;height:4px;background:var(--primary);margin:16px 0 20px;border-radius:2px;"></div>
          <p class="about-text" style="font-style:italic;color:var(--primary);font-weight:600;">"{{ catalog.configuracion.lema }}"</p>
          <p class="about-text">
            Somos una empresa familiar especializada en la venta de materiales de acabados y diseño de interiores, ubicada en Tesalia, Huila. Atendemos a clientes de Tesalia y municipios cercanos.
          </p>
          <p class="about-text">
            Con más de 10 años de experiencia, ofrecemos pinturas, vinilos, materiales de construcción y servicios de instalación profesional, convirtiéndonos en el aliado estratégico de constructores, decoradores y hogares.
          </p>
          <div class="about-items">
            <div class="about-item" v-for="item in aboutItems" :key="item">
              <i class="ri-check-line"></i><span>{{ item }}</span>
            </div>
          </div>
          <div class="d-flex gap-16" style="flex-wrap:wrap;">
            <a :href="catalog.configuracion.mapa_ver_url" target="_blank" rel="noopener" class="btn btn-primary">
              <i class="ri-map-pin-line"></i> Encuéntranos
            </a>
            <RouterLink to="/productos" class="btn btn-outline-red">
              <i class="ri-store-line"></i> Ver Catálogo
            </RouterLink>
            <RouterLink to="/servicios" class="btn btn-outline-red">
              <i class="ri-tools-line"></i> Ver Servicios
            </RouterLink>
          </div>
        </div>
      </div>
    </div>
  </section>

  <!-- TESTIMONIOS -->
  <section class="section">
    <div class="container">
      <div class="section-header">
        <span class="section-eyebrow">Reseñas</span>
        <h2 class="section-title">Lo que dicen nuestros clientes</h2>
      </div>
      <Swiper
        class="testimonials-swiper"
        :slides-per-view="1"
        :space-between="24"
        loop
        grab-cursor
        :autoplay="{ delay: 6000, disableOnInteraction: false }"
        :pagination="{ clickable: true }"
        :modules="[Pagination, Autoplay]"
        :breakpoints="{ 640: { slidesPerView: 1.5 }, 768: { slidesPerView: 2 }, 1200: { slidesPerView: 3 } }"
      >
        <SwiperSlide v-for="t in testimonials" :key="t.name">
          <div class="testimonial-card">
            <div class="testimonial-stars">{{ '★'.repeat(t.stars) }}{{ '☆'.repeat(5 - t.stars) }}</div>
            <p class="testimonial-text">{{ t.text }}</p>
            <div class="testimonial-author">
              <div class="author-avatar">{{ t.initials }}</div>
              <div>
                <div class="author-name">{{ t.name }}</div>
                <div class="author-role">{{ t.role }}</div>
              </div>
            </div>
          </div>
        </SwiperSlide>
      </Swiper>
    </div>
  </section>

  <!-- CONTACTO -->
  <section class="section contact-section">
    <div class="container">
      <div class="section-header">
        <span class="section-eyebrow" style="color:rgba(255,255,255,0.5);">Contáctanos</span>
        <h2 class="section-title" style="color:white;">¿Tienes alguna pregunta?</h2>
        <p class="section-subtitle" style="color:rgba(255,255,255,0.55);">Estamos aquí para ayudarte. Escríbenos o llámanos.</p>
      </div>
      <div class="contact-grid">
        <div>
          <div class="contact-info-item">
            <div class="contact-icon"><i class="ri-map-pin-line"></i></div>
            <div>
              <div class="contact-label">Dirección</div>
              <div class="contact-value">{{ catalog.configuracion.direccion }}, {{ catalog.configuracion.departamento }}</div>
            </div>
          </div>
          <div class="contact-info-item">
            <div class="contact-icon"><i class="ri-phone-line"></i></div>
            <div>
              <div class="contact-label">Teléfono</div>
              <div class="contact-value">{{ catalog.configuracion.telefono }}</div>
            </div>
          </div>
          <div class="contact-info-item">
            <div class="contact-icon"><i class="ri-mail-line"></i></div>
            <div>
              <div class="contact-label">Email</div>
              <div class="contact-value">{{ catalog.configuracion.email }}</div>
            </div>
          </div>
          <div class="contact-info-item">
            <div class="contact-icon"><i class="ri-time-line"></i></div>
            <div>
              <div class="contact-label">Horario</div>
              <div class="contact-value">{{ catalog.configuracion.horario }}</div>
            </div>
          </div>
          <div class="d-flex gap-10 mt-20" style="flex-wrap:wrap;">
            <a :href="`https://wa.me/${catalog.configuracion.whatsapp}`" target="_blank" rel="noopener" class="btn btn-success">
              <i class="ri-whatsapp-line"></i> WhatsApp
            </a>
            <RouterLink to="/contacto" class="btn btn-outline" style="color:rgba(255,255,255,0.75);border-color:rgba(255,255,255,0.25);">
              Ver mapa <i class="ri-map-pin-line"></i>
            </RouterLink>
          </div>
        </div>

        <div>
          <form class="contact-form" @submit.prevent="submitContactForm">
            <div style="display:grid;grid-template-columns:1fr 1fr;gap:16px;">
              <div class="form-group">
                <label class="form-label required">Nombre</label>
                <input v-model="contactForm.nombre" type="text" class="form-control" placeholder="Tu nombre" required />
              </div>
              <div class="form-group">
                <label class="form-label required">Teléfono</label>
                <input v-model="contactForm.telefono" type="tel" class="form-control" placeholder="310 000 0000" required />
              </div>
            </div>
            <div class="form-group">
              <label class="form-label required">Email</label>
              <input v-model="contactForm.email" type="email" class="form-control" placeholder="correo@ejemplo.com" required />
            </div>
            <div class="form-group">
              <label class="form-label required">Mensaje</label>
              <textarea v-model="contactForm.mensaje" class="form-control" placeholder="¿En qué podemos ayudarte?" rows="4" required></textarea>
            </div>
            <button type="submit" class="btn btn-primary btn-block">
              <i class="ri-send-plane-line"></i> Enviar Mensaje
            </button>
          </form>
        </div>
      </div>
    </div>
  </section>

  <!-- CTA: CREAR CUENTA / COTIZAR -->
  <section class="home-cta-section">
    <div class="container">
      <template v-if="!auth.isAuthenticated">
        <h2 class="home-cta-title">Únete a Acabados 1A</h2>
        <p class="home-cta-sub">
          Crea tu cuenta gratis y guarda tus cotizaciones, agiliza tus compras y haz seguimiento a tus pedidos.
        </p>
        <div class="home-cta-actions">
          <RouterLink to="/registro" class="btn btn-white btn-lg">Crear cuenta gratis</RouterLink>
          <RouterLink to="/login" class="home-cta-link">¿Ya tienes cuenta? Inicia sesión</RouterLink>
        </div>
      </template>
      <template v-else>
        <h2 class="home-cta-title">¿Tienes un proyecto en mente{{ primerNombre ? `, ${primerNombre}` : '' }}?</h2>
        <p class="home-cta-sub">
          Solicita tu cotización personalizada y nuestro equipo te asesora sin costo.
        </p>
        <div class="home-cta-actions">
          <RouterLink to="/cotizaciones" class="btn btn-white btn-lg">Solicitar Cotización</RouterLink>
          <RouterLink to="/productos" class="home-cta-link">Explorar catálogo</RouterLink>
        </div>
      </template>
    </div>
  </section>
</template>

<style scoped>
.about-content { text-align: left; }
</style>
