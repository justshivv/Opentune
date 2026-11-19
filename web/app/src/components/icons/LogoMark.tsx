import markSvg from '@/assets/logo-mark.svg?raw';

// OpenTune's mark, the same file the desktop app ships: the white wave on its black tile.
const markup = markSvg.replace(/width="\d+" height="\d+"/, 'width="100%" height="100%"');
// The wave alone, without the tile, in currentColor so it follows the text colour it sits in.
// The tile leaves room around the wave, so the bare wave is drawn from a square box around it.
const bareMarkup = markup
  .replace(/<rect[^>]*\/>/, '')
  .replace(/fill="#fff"/, 'fill="currentColor"')
  .replace(/viewBox="[^"]*"/, 'viewBox="17 17 74 74"');

export function LogoMark({ size = 20, className, bare = false }: { size?: number; className?: string; bare?: boolean }) {
  return (
    <span
      aria-hidden="true"
      className={className}
      style={{ display: 'inline-flex', width: size, height: size }}
      dangerouslySetInnerHTML={{ __html: bare ? bareMarkup : markup }}
    />
  );
}
