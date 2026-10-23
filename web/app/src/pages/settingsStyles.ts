/*
 * Grouped inset rows, the settings idiom the app uses elsewhere: the
 * Extension, Settings, Discord and Listen Together pages.
 */

const row = 'relative flex items-center justify-between px-4 py-[13px] type-title-3 hairline-[16px]';
const integration = 'relative grid items-center gap-x-3 px-4 py-[13px] type-title-3 hairline-[56px]';
const field = 'w-[220px] max-w-[50%] flex-none rounded-[10px] border-0 bg-quaternary px-3 py-1.5 type-body text-primary';

export const settingsUi = {
  /** The page title and its rows as one column, centred in the page. */
  centered: 'mx-auto max-w-[calc(640px+2*var(--body-gutter))]',
  body: 'mx-(--body-gutter) mt-5 mb-[120px] max-w-[640px]',
  group: 'overflow-hidden rounded-[18px] bg-quinary',
  sectionHeader: 'mx-4 mt-8 mb-2.5 type-subhead-emphasized text-secondary',
  footer: 'mx-4 mt-2 type-callout text-secondary',

  row,
  /** A row holding a switch, a select or a field: the whole row is its label. */
  controlRow: `${row} cursor-pointer gap-4`,
  rowText: 'flex flex-col gap-0.5',
  rowDetail: 'type-callout text-secondary',
  value: 'inline-flex items-center gap-1.5 text-right type-body-reduced text-secondary',
  dot: 'size-2 rounded-full bg-green',

  /** Download rows: browser logo, title and detail, chevron. */
  download:
    'relative grid grid-cols-[28px_minmax(0,1fr)_auto] items-center gap-x-4 px-4 py-[11px] hover:bg-quinary hairline-[60px]',
  updateDot: 'size-2.5 justify-self-center rounded-full bg-key',
  downloadText: 'flex min-w-0 flex-col gap-0.5',
  downloadTitle: 'type-title-3-emphasized text-key',
  downloadDetail: 'type-body text-secondary',
  chevron: 'text-tertiary',
  steps: 'mx-4 mt-2.5 list-decimal ps-[18px] type-body-tall text-secondary [&>li+li]:mt-1',

  /** Integration rows: a coloured app-icon square, the name, the state. */
  integration: `${integration} grid-cols-[28px_minmax(0,1fr)_auto]`,
  /** An integration row without the icon square. */
  integrationPlain: `${integration} grid-cols-[minmax(0,1fr)_auto]`,
  integrationIcon: 'grid size-7 place-items-center rounded-[9px] text-white',
  integrationName: 'min-w-0',
  /** Settings: an integration row that opens a page of its own. */
  linkRow: 'w-full cursor-pointer text-start hover:bg-quaternary',
  /** A row's own action, after its status (Connect, Disconnect). */
  inlineButton: 'ml-2 rounded-[12px] bg-quaternary px-2.5 py-[3px] type-callout-emphasized text-primary disabled:opacity-50',
  inlineLink: 'text-key',

  switch: [
    // The app's switch: the accent colour when on, the knob growing a touch as it does.
    'relative h-[26px] w-[44px] flex-none cursor-pointer appearance-none rounded-[13px] bg-quaternary transition-[background-color] duration-200 checked:bg-key-bg',
    'after:absolute after:top-[3px] after:left-[3px] after:size-5 after:rounded-full after:bg-white after:shadow-[0_1px_3px_rgb(0_0_0/0.3)] after:transition-[translate,scale] after:duration-300 after:ease-[cubic-bezier(0.34,1.4,0.64,1)] checked:after:translate-x-[18px] active:after:scale-x-110',
  ].join(' '),
  select: field,
  input: field,
};
