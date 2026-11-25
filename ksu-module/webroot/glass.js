// Liquid Glass refraction for floating controls.
// Same approach as liquid-glass-react: an SVG displacement map bends the backdrop near the rim,
// and the red, green and blue channels are displaced by slightly different amounts (chromatic
// aberration). The map is drawn locally on a canvas so the strict CSP (img-src 'self' data:) holds.
// Chromium WebView supports SVG filters in backdrop-filter; elsewhere the CSS blur, tint and rim remain.

const SVG = 'http://www.w3.org/2000/svg';
const XLINK = 'http://www.w3.org/1999/xlink';
const CHANNELS = [
  ['R', '1 0 0 0 0  0 0 0 0 0  0 0 0 0 0  0 0 0 1 0'],
  ['G', '0 0 0 0 0  0 1 0 0 0  0 0 0 0 0  0 0 0 1 0'],
  ['B', '0 0 0 0 0  0 0 0 0 0  0 0 1 0 0  0 0 0 1 0'],
];

const plainSurfaces = matchMedia('(prefers-reduced-transparency: reduce), (prefers-contrast: more), (forced-colors: active)');
const supported = typeof CSS !== 'undefined'
  && typeof ResizeObserver === 'function'
  && CSS.supports('backdrop-filter', 'url(#lens) blur(1px)');

let count = 0;

function node(name, attributes) {
  const element = document.createElementNS(SVG, name);
  for (const [key, value] of Object.entries(attributes)) element.setAttribute(key, String(value));
  return element;
}

// R/G encode x/y displacement (128 = none). Pixels near the rounded-rect edge sample the backdrop
// from further inside, strongest at the rim and fading to zero at `depth` pixels in.
function lensMap(width, height, radius, depth) {
  const canvas = document.createElement('canvas');
  canvas.width = width;
  canvas.height = height;
  const context = canvas.getContext('2d');
  const image = context.createImageData(width, height);
  const halfWidth = width / 2;
  const halfHeight = height / 2;
  const corner = Math.min(radius, halfWidth, halfHeight);
  for (let y = 0; y < height; y++) {
    const py = y + .5 - halfHeight;
    const qy = Math.abs(py) - halfHeight + corner;
    for (let x = 0; x < width; x++) {
      const px = x + .5 - halfWidth;
      const qx = Math.abs(px) - halfWidth + corner;
      const ox = Math.max(qx, 0);
      const oy = Math.max(qy, 0);
      const outside = Math.hypot(ox, oy);
      const inset = corner - outside - Math.min(Math.max(qx, qy), 0);
      let nx = 0;
      let ny = 0;
      if (outside > 0) {
        nx = ox / outside;
        ny = oy / outside;
      } else if (qx > qy) {
        nx = 1;
      } else {
        ny = 1;
      }
      const strength = Math.min(1, Math.max(0, 1 - inset / depth)) ** 2;
      const index = (y * width + x) * 4;
      image.data[index] = 128 - Math.sign(px) * nx * strength * 127;
      image.data[index + 1] = 128 - Math.sign(py) * ny * strength * 127;
      image.data[index + 2] = 128;
      image.data[index + 3] = 255;
    }
  }
  context.putImageData(image, 0, 0);
  return canvas.toDataURL('image/png');
}

export function attachLens(element, {depth, scale, aberration = .025}) {
  const defs = document.getElementById('lens-defs');
  if (!supported || plainSurfaces.matches || !element || !defs) return;

  const id = `lens-${++count}`;
  const filter = node('filter', {
    id,
    x: 0,
    y: 0,
    filterUnits: 'userSpaceOnUse',
    primitiveUnits: 'userSpaceOnUse',
    'color-interpolation-filters': 'sRGB',
  });
  const map = node('feImage', {x: 0, y: 0, preserveAspectRatio: 'none', result: 'map'});
  filter.append(map);
  CHANNELS.forEach(([channel, matrix], step) => {
    filter.append(node('feDisplacementMap', {
      in: 'SourceGraphic',
      in2: 'map',
      scale: (scale * (1 + aberration * step)).toFixed(2),
      xChannelSelector: 'R',
      yChannelSelector: 'G',
      result: `shift${channel}`,
    }));
    filter.append(node('feColorMatrix', {in: `shift${channel}`, type: 'matrix', values: matrix, result: `only${channel}`}));
  });
  filter.append(node('feBlend', {in: 'onlyR', in2: 'onlyG', mode: 'screen', result: 'onlyRG'}));
  filter.append(node('feBlend', {in: 'onlyRG', in2: 'onlyB', mode: 'screen'}));
  defs.append(filter);

  let size = '';
  const update = () => {
    const width = Math.round(element.offsetWidth);
    const height = Math.round(element.offsetHeight);
    if (!width || !height || `${width}x${height}` === size) return;
    size = `${width}x${height}`;
    const radius = parseFloat(getComputedStyle(element).borderTopLeftRadius) || 0;
    const url = lensMap(width, height, radius, depth);
    for (const target of [filter, map]) {
      target.setAttribute('width', width);
      target.setAttribute('height', height);
    }
    map.setAttribute('href', url);
    map.setAttributeNS(XLINK, 'xlink:href', url);
    element.style.setProperty('--lens', `url(#${id})`);
    element.classList.add('lens');
  };

  new ResizeObserver(() => requestAnimationFrame(update)).observe(element);
  update();
}
