# Validación en campo (F5)

Lista para el aforo real que cierra la fase F5: un aforo hecho con el iPhone, coherente con RIVeR escritorio.

## Antes de salir

- [ ] Reinstalar la app con iloader. Con un Apple ID gratuito caduca a los 7 días; los proyectos se conservan si se
      reinstala encima, sin borrar la app.
- [ ] Batería cargada (y batería externa).
- [ ] Trípode o soporte fijo: la app no estabiliza el video (llega en F7).
- [ ] Batimetría de la sección en CSV copiada al iPhone (app Archivos), si ya existe.

## Lo que hay que llevar medido del campo

El mismo requisito que en escritorio:

- [ ] **Las distancias o puntos de control para la georreferenciación**
  - Dron (UAV): 2 puntos y la distancia entre ellos.
  - Oblicua: 4 puntos (1 y 2 en una orilla, 3 y 4 en la otra) y las 6 distancias entre ellos.
  - Estación fija (3D): puntos de control con coordenadas.
- [ ] **La batimetría de la sección** (estación y cota de cada punto, de orilla izquierda a derecha).
- [ ] **El nivel del agua** en el momento del video, en la misma referencia que la batimetría.

## En el sitio

- [ ] Grabar con la cámara de la app (fps fijos, foco y exposición bloqueados), 10–20 s, con trazadores visibles en
      la superficie (espuma, hojas, textura).
- [ ] Tomar fotos de los puntos de control para poder ubicarlos luego en la imagen.
- [ ] Hacer el flujo completo en la app: frames → georreferenciación → sección → PIV → resultados.

## Qué anotar

- [ ] Tiempo que tarda el PIV en el iPhone (número de frames y resolución).
- [ ] Caudal, velocidad media, ancho y área que da la app.
- [ ] Residuos de la georreferenciación.
- [ ] Problemas de uso en el sitio (sol en la pantalla, marcar puntos, batería, calor).

## Después, en la computadora

- [ ] Exportar el proyecto desde la app (paso 6) y abrirlo en RIVeR escritorio 3.8.3.
- [ ] Procesarlo en escritorio y comparar caudal y velocidades con la app. Diferencias esperadas por las
      correcciones del port: ver la tabla «Errores de RIVeR corregidos» en `PLAN.md`.
- [ ] Si coincide, marcar F5 ✅ en `PLAN.md`.
