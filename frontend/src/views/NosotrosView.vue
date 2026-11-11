<script setup>
// RNF10/RNF11 - página "Nosotros" ("Quiénes somos"): historia, misión/visión, galería del local y valores.
// Nota: las fotos de la galería son de stock (mismo criterio que el resto del sitio) mientras
// no se cuente con fotografías reales del local/equipo. Basta con reemplazar las URLs del array
// `galeria` de abajo cuando se tengan.
import { ref } from 'vue'
import { useCatalogStore } from '../stores/catalog'

const catalog = useCatalogStore()

const stats = [
  { icon: 'ri-calendar-2-line', value: '8+', label: 'Años de experiencia' },
  { icon: 'ri-emotion-happy-line', value: '1.200+', label: 'Clientes satisfechos' },
  { icon: 'ri-store-2-line', value: '350+', label: 'Productos disponibles' },
  { icon: 'ri-map-pin-2-line', value: '6', label: 'Municipios atendidos' },
]

const aboutItems = [
  'Pinturas y Vinilos tipo 1, 2 y 3',
  'Instalación Drywall y PVC',
  'Estuco plástico y Graniplast',
  'Pegacol y materiales adhesivos',
  'Herramientas y accesorios',
  'Asesoría en diseño de interiores',
]

const valores = [
  { icon: 'ri-team-line', title: 'Familia y Cercanía', desc: 'Somos una empresa familiar: tratamos a cada cliente como parte de nuestra comunidad, con atención cercana y de confianza.' },
  { icon: 'ri-hand-heart-line', title: 'Compromiso Real', desc: 'Cumplimos lo que prometemos: tiempos de entrega, garantías y acompañamiento en cada etapa de tu proyecto.' },
  { icon: 'ri-lightbulb-flash-line', title: 'Ideas que Inspiran', desc: 'Convertimos tu espacio con propuestas creativas de diseño, siguiendo nuestro lema: grandes ideas, grandes diseños.' },
  { icon: 'ri-award-line', title: 'Calidad Constante', desc: 'Trabajamos con marcas reconocidas y procesos cuidadosos para resultados duraderos en cada acabado.' },
]

const galeria = [
  { img: 'https://images.unsplash.com/photo-1562259929-b4e1fd3aef09?w=900&q=80', caption: 'Nuestro punto de venta en Tesalia' },
  { img: 'https://images.unsplash.com/photo-1581578731548-c64695cc6952?w=700&q=80', caption: 'Amplio stock de materiales de acabado' },
  { img: 'https://images.unsplash.com/photo-1572981779307-38b8cabb2407?w=700&q=80', caption: 'Herramientas para cada proyecto' },
  { img: 'https://images.unsplash.com/photo-1504307651254-35680f356dfd?w=700&q=80', caption: 'Asesoría personalizada en cada compra' },
  { img: 'https://images.unsplash.com/photo-1558618666-fcd25c85cd64?w=700&q=80', caption: 'Pinturas y vinilos de las mejores marcas' },
  { img: 'https://images.unsplash.com/photo-1589939705384-5185137a7f0f?w=700&q=80', caption: 'Calidad que se nota en cada acabado' },
]

const imagenLightbox = ref(null)
</script>

<template>
  <section class="services-hero">
    <div class="services-hero-bg">
      <img src="https://images.unsplash.com/photo-1504307651254-35680f356dfd?w=1600&q=80" alt="Nosotros - Acabados y Diseños 1A" />
    </div>
    <div class="container">
      <div class="breadcrumb">
        <span class="breadcrumb-item"><RouterLink to="/"><i class="ri-home-4-line"></i> Inicio</RouterLink></span>
        <span class="breadcrumb-sep"><i class="ri-arrow-right-s-line"></i></span>
        <span class="breadcrumb-item active">Nosotros</span>
      </div>
      <h1 class="services-hero-title">Conoce la historia detrás de <em>Acabados y Diseños 1A</em></h1>
      <p class="services-hero-desc">
        Una empresa familiar de Tesalia, Huila, comprometida con transformar espacios y construir relaciones de confianza con cada cliente.
      </p>
    </div>
  </section>

  <div class="container">
    <div class="services-stats-card">
      <div class="services-stat" v-for="s in stats" :key="s.label">
        <i :class="s.icon"></i><strong>{{ s.value }}</strong><span>{{ s.label }}</span>
      </div>
    </div>
  </div>

  <!-- QUIÉNES SOMOS -->
  <section class="section">
    <div class="container">
      <div class="about-section">
        <div class="nosotros-story-img">
          <img src="https://images.unsplash.com/photo-1562259929-b4e1fd3aef09?w=700&q=80" alt="Local de Acabados y Diseños 1A" />
        </div>
        <div class="about-content">
          <div class="about-tag">
            <i class="ri-team-line"></i>
            <span>Quiénes somos</span>
          </div>
          <h2 class="section-title mb-0" style="text-align:left;">Empresa familiar, comprometida con tus espacios</h2>
          <div style="width:60px;height:4px;background:var(--primary);margin:16px 0 20px;border-radius:2px;"></div>
          <p class="about-text" style="font-style:italic;color:var(--primary);font-weight:600;">"{{ catalog.configuracion.lema }}"</p>
          <p class="about-text">
            Somos una empresa familiar comprometida con dar color y belleza a tus obras y construcciones. Nos encargamos de la decoración de interiores y de todos los acabados que necesita tu proyecto, elaborando nuestros propios productos para garantizar su calidad y durabilidad.
          </p>
          <p class="about-text">
            Con sede en Tesalia, Huila, y más de 10 años en el negocio, atendemos a clientes de Tesalia y municipios cercanos. Contamos con un equipo humano con la experiencia y el conocimiento para garantizarte un trabajo y unos productos de confianza.
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
            <RouterLink to="/contacto" class="btn btn-outline-red">
              <i class="ri-chat-3-line"></i> Contáctanos
            </RouterLink>
          </div>
        </div>
      </div>
    </div>
  </section>

  <!-- QUÉ HACEMOS -->
  <section class="section nosotros-quehacemos">
    <div class="container">
      <div class="section-header">
        <span class="section-eyebrow">Qué hacemos</span>
        <h2 class="section-title">Todo lo que necesitas, en un solo lugar</h2>
        <p style="color:var(--text-light);max-width:560px;margin:0 auto;">Amplia oferta en la línea de acabados para construcción, remodelación y diseño de espacios.</p>
      </div>
      <div class="quehacemos-grid">
        <RouterLink to="/productos" class="quehacemos-card">
          <div class="cat-icon"><i class="ri-store-2-line"></i></div>
          <h3>Catálogo</h3>
          <p>Pinturas, vinilos, estuco, Graniplast, pegantes y materiales de acabado elaborados con calidad garantizada.</p>
        </RouterLink>
        <RouterLink to="/servicios" class="quehacemos-card">
          <div class="cat-icon"><i class="ri-tools-line"></i></div>
          <h3>Servicios</h3>
          <p>Instalación de Drywall y PVC, aplicación de pintura y acabados con mano de obra experta y garantizada.</p>
        </RouterLink>
        <RouterLink to="/cotizaciones" class="quehacemos-card">
          <div class="cat-icon"><i class="ri-file-list-3-line"></i></div>
          <h3>Cotizaciones</h3>
          <p>Asesoría y presupuestos a la medida de tu proyecto, sin compromiso y en menos de 24 horas.</p>
        </RouterLink>
      </div>
    </div>
  </section>

  <!-- MISIÓN Y VISIÓN -->
  <section class="section" style="background:var(--off-white);padding-top:0;">
    <div class="container">
      <div class="mv-grid">
        <div class="mv-card">
          <div class="mv-icon"><i class="ri-flag-line"></i></div>
          <h3 class="mv-title">Nuestra Misión</h3>
          <p class="mv-text">{{ catalog.configuracion.mision }}</p>
        </div>
        <div class="mv-card">
          <div class="mv-icon"><i class="ri-eye-line"></i></div>
          <h3 class="mv-title">Nuestra Visión</h3>
          <p class="mv-text">{{ catalog.configuracion.vision }}</p>
        </div>
      </div>
    </div>
  </section>

  <!-- GALERÍA DEL LOCAL -->
  <section class="section">
    <div class="container">
      <div class="section-header">
        <span class="section-eyebrow">Conócenos</span>
        <h2 class="section-title">Así es Acabados y Diseños 1A</h2>
        <p style="color:var(--text-light);max-width:560px;margin:0 auto;">Un vistazo a nuestro local, nuestros productos y la forma en que atendemos cada proyecto.</p>
      </div>
      <div class="nosotros-gallery">
        <div
          v-for="(g, i) in galeria"
          :key="g.img"
          class="nosotros-gallery-item"
          :class="{ 'nosotros-gallery-item-lg': i === 0 }"
          @click="imagenLightbox = g.img"
        >
          <img :src="g.img" :alt="g.caption" loading="lazy" />
          <div class="nosotros-gallery-caption">{{ g.caption }}</div>
        </div>
      </div>
    </div>
  </section>

  <!-- NUESTROS VALORES -->
  <section class="section" style="background:var(--off-white);">
    <div class="container">
      <div class="section-header">
        <span class="section-eyebrow">Nuestros valores</span>
        <h2 class="section-title">Lo que nos define</h2>
      </div>
      <div class="why-grid">
        <div class="why-card" v-for="v in valores" :key="v.title">
          <div class="why-icon"><i :class="v.icon"></i></div>
          <h3 class="why-title">{{ v.title }}</h3>
          <p class="why-desc">{{ v.desc }}</p>
        </div>
      </div>
    </div>
  </section>

  <!-- CTA FINAL -->
  <section class="home-cta-section">
    <div class="container">
      <h2 class="home-cta-title">¿Quieres conocernos en persona?</h2>
      <p class="home-cta-sub">Visítanos en Tesalia, Huila, o escríbenos por WhatsApp. Con gusto te atendemos.</p>
      <div class="d-flex gap-16" style="flex-wrap:wrap;justify-content:center;">
        <a :href="catalog.configuracion.mapa_ver_url" target="_blank" rel="noopener" class="btn btn-white btn-lg">
          <i class="ri-map-pin-line"></i> Encuéntranos
        </a>
        <a :href="`https://wa.me/${catalog.configuracion.whatsapp}`" target="_blank" rel="noopener" class="btn btn-success btn-lg">
          <i class="ri-whatsapp-line"></i> Escríbenos por WhatsApp
        </a>
      </div>
    </div>
  </section>

  <div v-if="imagenLightbox" class="lightbox-overlay" @click.self="imagenLightbox = null">
    <button class="lightbox-close" aria-label="Cerrar" @click="imagenLightbox = null"><i class="ri-close-line"></i></button>
    <img :src="imagenLightbox" alt="Acabados y Diseños 1A" class="lightbox-img" />
  </div>
</template>

<style scoped>
.nosotros-story-img { position: relative; border-radius: var(--radius-lg); overflow: hidden; }
.nosotros-story-img img { width: 100%; height: 440px; object-fit: cover; display: block; }
.nosotros-story-img::before {
  content: ''; position: absolute; inset: 16px; border: 3px solid var(--primary);
  border-radius: var(--radius-lg); z-index: 1; pointer-events: none; opacity: 0.5;
}

.mv-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 24px; }
.mv-card {
  background: white; border: 1px solid var(--border); border-radius: var(--radius-lg);
  padding: 32px; box-shadow: var(--shadow);
}
.mv-icon {
  width: 56px; height: 56px; border-radius: 50%; background: rgba(192,57,43,0.08);
  display: flex; align-items: center; justify-content: center; font-size: 1.4rem;
  color: var(--primary); margin-bottom: 18px;
}
.mv-title { font-family: var(--font-main); font-weight: 800; font-size: 1.15rem; color: var(--secondary); margin-bottom: 12px; }
.mv-text { font-size: 0.92rem; color: var(--text-light); line-height: 1.75; }

.nosotros-gallery {
  display: grid; grid-template-columns: repeat(4, 1fr); grid-auto-rows: 160px; gap: 14px;
}
.nosotros-gallery-item-lg { grid-column: span 2; grid-row: span 2; }
.nosotros-gallery-item {
  position: relative; border-radius: var(--radius); overflow: hidden; cursor: pointer;
}
.nosotros-gallery-item img {
  width: 100%; height: 100%; object-fit: cover; display: block; transition: transform 0.5s ease;
}
.nosotros-gallery-item:hover img { transform: scale(1.08); }
.nosotros-gallery-caption {
  position: absolute; left: 0; right: 0; bottom: 0; padding: 10px 14px;
  background: linear-gradient(to top, rgba(26,26,46,0.88), transparent);
  color: white; font-size: 0.78rem; font-weight: 600; opacity: 0; transform: translateY(6px);
  transition: var(--transition);
}
.nosotros-gallery-item:hover .nosotros-gallery-caption { opacity: 1; transform: translateY(0); }

.nosotros-quehacemos { background: linear-gradient(180deg, var(--off-white) 0%, white 100%); }
.quehacemos-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 24px; }
.quehacemos-card {
  background: white; border: 1.5px solid var(--border); border-radius: var(--radius-lg);
  padding: 36px 28px; text-align: center; transition: var(--transition);
  box-shadow: 0 6px 20px rgba(26,26,46,0.04);
}
.quehacemos-card:hover {
  border-color: var(--primary); transform: translateY(-6px);
  box-shadow: 0 16px 34px rgba(192,57,43,0.16);
}
.quehacemos-card .cat-icon { width: 64px; height: 64px; font-size: 1.7rem; margin: 0 auto 20px; }
.quehacemos-card:hover .cat-icon { background: var(--primary); color: white; }
.quehacemos-card h3 { font-family: var(--font-main); font-weight: 800; font-size: 1.1rem; color: var(--secondary); margin-bottom: 10px; }
.quehacemos-card p { font-size: 0.88rem; color: var(--text-light); line-height: 1.65; }

@media (max-width: 992px) {
  .nosotros-story-img img { height: 320px; }
}
@media (max-width: 768px) {
  .mv-grid { grid-template-columns: 1fr; }
  .quehacemos-grid { grid-template-columns: 1fr; gap: 18px; }
  .nosotros-gallery { grid-template-columns: repeat(2, 1fr); grid-auto-rows: 140px; }
  .nosotros-gallery-item-lg { grid-column: span 2; grid-row: span 1; }
}
@media (max-width: 480px) {
  .nosotros-gallery { grid-template-columns: 1fr; }
  .nosotros-gallery-item-lg { grid-column: span 1; }
  .mv-card { padding: 22px; }
}
</style>
