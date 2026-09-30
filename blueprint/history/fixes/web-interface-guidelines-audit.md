# Fix: Web Interface Guidelines audit findings

**Type:** Fix
**Status:** verified
**Branch:** fix/web-interface-guidelines-audit

## The problem

An audit of the dashboard against Vercel's Web Interface Guidelines (screenshots
in `docs/images/`, then confirmed in the source) found five real defects:

1. **Event drawer hidden under the app bar.** `AppLayout.tsx` sets the AppBar to
   `zIndex: drawer + 1` (1201) so it sits above the permanent nav drawer. The
   Events detail drawer (a temporary MUI `Drawer`, z-index 1200) therefore
   renders below the bar. Its top 48 px, which holds the event ID title and the
   Close button, is covered, so the Close button cannot be clicked and the
   scrim does not dim the header. Guideline: sticky headers must not cover
   focused elements.
2. **Locale follows the browser, copy does not.** Every formatter in
   `lib/format.ts` passes `undefined` as the locale. The UI text is English
   only, but a Turkish browser shows `100.197` (a thousands separator that an
   English reader takes for a decimal) and `30 Eyl 2026`.
3. **Number formatting is inconsistent.** KPI cards and the Services table use
   `formatCount`, but the Severity chart's axis ticks, bar labels and tooltips
   and the Timeline chart's axis ticks and tooltips print raw numbers
   (`50097`, `10000`).
4. **Clipped y-axis labels.** Both charts fix `YAxis width={40}`. A five-digit
   tick such as `50000` is cut off at the left edge, and grouped ticks such as
   `50,000` need more room.
5. **Chip contrast below AA.** `contrastText` in `lib/colors.ts` returns white
   on everything except the WARNING amber. White on `#1e88e5` is about 3.7:1,
   on `#ef6c00` about 3.1:1, on `#e53935` about 4.2:1 and on `#43a047` about
   3.3:1. The chips use 11 px bold text, which needs 4.5:1.
6. **Layout shift between routes.** The Dashboard page scrolls and shows a
   scrollbar; the Services page does not. The header controls jump about 15 px
   when navigating between them.

## The fix

- **Drawer stacking.** Give the Events detail drawer a z-index above the app
  bar (`theme.zIndex.drawer + 2` via `sx`), so the title and Close button are
  visible and the scrim covers the header. The permanent nav and app bar
  ordering in `AppLayout` stays as it is. Toasts (Snackbar, 1400) stay above.
- **One locale.** Add `const LOCALE = 'en-GB'` in `lib/format.ts`, pass it to
  all three `Intl.DateTimeFormat` instances, and make `formatCount` use one
  shared `Intl.NumberFormat(LOCALE)`. `en-GB` keeps the current 24 hour clock
  and is the natural fit for a European rail operator. This is the one
  judgment call in the spec: the alternative, translating the UI, is a
  feature, not a fix.
- **One number format.** Use `formatCount` for the Severity chart's `YAxis`
  `tickFormatter`, its `LabelList` `formatter` and its tooltip value, and for
  the Timeline chart's `YAxis` `tickFormatter` and tooltip values.
- **Axis width.** Raise both `YAxis` widths to 52 so grouped five-digit ticks
  fit.
- **Chip text color.** Make `contrastText(background)` return `#000` or `#fff`,
  whichever has the higher WCAG contrast ratio against the given hex color,
  computed from relative luminance. All four severity colors and the three
  health colors resolve to black, which passes AA (about 5 to 7:1). The color
  mapping itself stays untouched, so the charts keep their colors.
- **Scrollbar gutter.** Add `scrollbar-gutter: stable` on `html` through the
  theme's `MuiCssBaseline` `styleOverrides` (`CssBaseline` is already rendered
  in `AppProviders.tsx`).

Must not break: the drawer's title `id`/`aria-labelledby` wiring, the existing
`role="img"` labels on both charts, the filter, status-change and toast
behavior, and the existing tests that compare against
`(1234).toLocaleString()` (jsdom's default `en-US` renders the same `1,234` as
`en-GB`).

No new dependency, no i18n framework, no theme-color or design overhaul.

### Deliberately not in this fix

Audit items that turned out to be non-defects or are separate work:

- Search placeholder ellipsis: the field already has a label and a placeholder,
  and `coding-standards.md` asks to avoid the ellipsis character.
- Charts without a text alternative and the URL-as-state gap: both already
  exist (`role="img"` labels; filters live in the URL).
- Title Case headings, raw enum casing (`ACKNOWLEDGED`), the 5+1 health-card
  wrap, linking KPI and health cards to filtered views: design preferences,
  not defects. Raise them separately if wanted.

## Build steps

- [x] **1. Drawer stacking and layout shift.**
  Raise the detail drawer above the app bar in `EventDetailDrawer.tsx`; add the
  `scrollbar-gutter` override in `theme.ts`.
  **Done when:** in the running app on `/events`, opening an event shows its ID
  title and an enabled Close button at the top of the drawer, the scrim dims the
  header, and the header controls stay put when switching between Dashboard and
  Services. `npm test`, `npm run lint` and `npm run build` are green.

- [x] **2. One locale and one number format.**
  Change `lib/format.ts` as above and route the chart axes, labels and tooltips
  through `formatCount`; widen both `YAxis`. Add to `format.test.ts`:
  `formatCount(100197)` is `100,197`, and `formatDateTime`/`formatTime` output is
  independent of the process default locale (assert the pinned `en-GB` shape,
  for example a 24 hour time).
  **Done when:** the new tests pass with the rest of `npm test`, lint and build,
  and no chart tick or label is clipped or ungrouped in the browser.

- [x] **3. Chip contrast.**
  Rewrite `contrastText` to pick the higher-contrast of black and white. Add
  `lib/colors.test.ts`: every color in `SEVERITY_COLORS` and `HEALTH_COLORS`
  yields text with a contrast ratio of at least 4.5:1 against its background.
  **Done when:** the test passes, `npm test`, lint and build are green, and the
  chips read as black text on their colors in both themes.

- [x] **4. Live check and screenshots.**
  Rebuild with `docker compose up -d --build --wait frontend`. Check every
  point in Verify below in the browser under a non-English browser locale
  (for example Playwright `locale: 'tr-TR'`). Then retake the four images in
  `docs/images/` (`dashboard.png`, `dashboard-dark.png`, `events-detail.png`,
  `services.png`) from a fresh stack with a small producer burst, so the
  services are not all DOWN.
  **Done when:** the four screenshots show `en-GB`-formatted numbers, an
  unclipped chart axis, a drawer with a visible title and Close button, and no
  console errors besides the browser's own failed-request lines.

## Verify

- Automated: the new `format.test.ts` and `colors.test.ts` cases plus the full
  frontend suite, lint and build.
- Manual, in the browser with a `tr-TR` locale: Dashboard and Services show
  `100,197` style numbers and `30 Sept 2026` style dates; chart axes are not
  clipped; the Events drawer shows its title and a working Close button; the
  header does not shift between routes; chip text is black and readable in
  Light and Dark.

## Implementation notes

- Drawer: `sx={(theme) => ({ zIndex: theme.zIndex.drawer + 2 })}` on the Events
  detail `Drawer`. In a `tr-TR` browser the Close button is the topmost element
  at its position and the scrim covers the header.
- `contrastText` now returns `#000000` or `#ffffff` (full hex, so
  `contrastRatio` can parse its own output). All seven severity and health
  colors resolve to black.
- The Dashboard and Services page tests compared against
  `(1234).toLocaleString()`, which is `1.234` under the Turkish default locale
  of the dev machine, so they were locale-dependent themselves. They now assert
  the literal `1,234` and `1,300`.
- Live check (tr-TR): KPIs read `1,858`, dates `30 Sept 2026`; the header sits at
  the same position on Dashboard and Services; no console errors.
- Deviation: the screenshots come from a fresh stack with about 1,500 events, not
  a small burst, so the counts pass 1,000 and show grouping. Any active CRITICAL
  makes a service DOWN, so all services show DOWN, as the README already says.
  No tick reached five digits, so five-digit axis clipping was not seen live; the
  four-digit ticks (`1,200`) fit.


<!-- blueprint:completion {"schemaVersion":1,"specBytes":8231,"specSha256":"2fd3af5d300b84048d75bb6751df2f5edba33befbea6c40e8259532c8a6de1c8","branch":"refs/heads/fix/web-interface-guidelines-audit","head":"b10ee29e539221b91d4e075bd5412cebbe003312","baseRef":"refs/heads/master","baseCommit":"b10ee29e539221b91d4e075bd5412cebbe003312","sourceTree":"5c6d41792a07bed2015b99dd6f82b1f348bbc05f","absentOptional":[]} -->
