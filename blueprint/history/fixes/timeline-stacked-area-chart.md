# Fix: Timeline stacked area chart

**Type:** Fix
**Status:** verified
**Branch:** fix/timeline-stacked-area-chart

## The problem

The dashboard's "Events over time (last 60 min)" panel draws 60 thin stacked
bars, one per minute, in `frontend/src/components/dashboard/TimelineChart.tsx`.
At dashboard width the bars are narrow and closely spaced, so the panel looks
dense and visually noisy. The operator wants a calmer chart that still shows
the event rate and severity mix at a glance.

## The fix

Replace the Recharts `BarChart` with a stacked `AreaChart` using smooth
curves, and reverse the stack so CRITICAL sits on the baseline. Only the
chart inside the panel changes:

- `AreaChart` with one `Area` per severity and the same `stackId="events"`.
- **CRITICAL at the bottom, INFO on top.** Render the areas in reverse
  `SEVERITIES` order (CRITICAL, MAJOR, WARNING, INFO); the first area drawn
  sits on the axis. In a stacked chart only the bottom band and the total are
  read against a flat line. CRITICAL is the severity operators act on, and
  INFO far outnumbers it (for example 900 vs 34). On top, CRITICAL would be a
  thin strip riding INFO's ups and downs. On the baseline, its trend reads
  directly, and the chart's upper edge still shows total volume.
- **Legend and tooltip stay INFO to CRITICAL.** Reversing the areas would
  otherwise reverse the legend, which follows draw order under today's
  `itemSorter={null}`. Sort legend and tooltip items by their position in
  `SEVERITIES` instead; both props accept a sort-key function in Recharts
  3.10.1. The legend then keeps today's INFO, WARNING, MAJOR, CRITICAL order.
  The tooltip lists items top band first, matching the stacked bands from top
  to bottom. Today's tooltip sorts alphabetically (CRITICAL, INFO, MAJOR,
  WARNING), so this also fixes its order.
- Curve `type="monotone"`. Unlike `natural` or `basis`, a monotone curve never
  overshoots the data, so a smoothed band never dips below zero or bulges past
  a minute's real count.
- Each area uses its `SEVERITY_COLORS` color for both stroke and fill. The
  fill is slightly translucent (around 0.7) so each band's top edge reads as
  a line.
- Keep `isAnimationActive={false}`. The chart re-renders every 5 s poll, and
  a replayed animation would be distracting.
- An area chart's tooltip cursor is a vertical line, not a filled rectangle.
  Change `Tooltip cursor` to a stroke in the divider color (Recharts'
  AreaChart only supports axis tooltips, so the tooltip still lists all four
  severities for the hovered minute).
- Unchanged: the `rows` data and `toTimelineRows`, axes and tick formatting,
  grid, severity colors, the 240 px height, the
  panel title, loading, error and empty states, and the `role="img"` label
  with the total.

Trade-off to accept: smoothing joins neighbouring minutes, so a single-minute
spike shows as a rounded hump instead of one tall bar. The tooltip still gives
exact per-minute counts.

No new dependency. `recharts` 3.10.1 is already installed and provides
`AreaChart` and `Area`.

## Build steps

- [x] **1. Swap the bars for stacked areas.** In `TimelineChart.tsx`, replace
  `BarChart`/`Bar` with `AreaChart`/`Area` as described above: reversed
  stack order, severity-order legend and tooltip sorting, and the line
  tooltip cursor.
  _Done when:_ `npm test`, `npm run lint` and `npm run build` pass in
  `frontend/`, and the existing dashboard tests (timeline label with its
  total, empty state, error state) pass unchanged.
- [x] **2. Live check.** Rebuild the frontend, open `/dashboard` with the
  producer sending events, and capture the panel in both light and dark mode.
  _Done when:_ the screenshot shows four smooth stacked bands in severity
  colors with no bars, with red CRITICAL on the axis and blue INFO on top.
  The legend still reads INFO, WARNING, MAJOR, CRITICAL. Hovering a minute
  shows all four counts in that same order.

- [x] **3. Neutral legend labels.** The legend's text uses the theme's
  secondary text color (`var(--mui-palette-text-secondary)`, the same as the
  axis ticks) instead of each series color. The colored marker beside each
  label still carries the severity's identity. Use the Recharts `Legend`
  `formatter` to wrap each label; marker colors, order and size don't change.
  _Done when:_ frontend tests, lint and build pass, and in the running app
  the legend labels are neutral gray in light and dark mode while the markers
  keep their severity colors.

## Verify

- Run `docker compose up -d --build --wait`, then
  `curl -X POST "http://localhost:8082/produce?count=200"`.
- Open http://localhost:3000/dashboard. "Events over time" should show smooth
  stacked colored bands instead of thin bars. Red CRITICAL should sit on the
  bottom axis, with blue INFO as the top band.
- The legend should read INFO, WARNING, MAJOR, CRITICAL, in neutral gray
  text beside colored markers.
- Hover over the chart. A vertical line and a tooltip should appear, listing
  the four severity counts for that minute in the same order.
- Switch the theme to Dark. The bands, grid and axis labels should stay
  readable.

## Implementation notes

- `STACK_ORDER` is `SEVERITIES` reversed, so the first area drawn is CRITICAL,
  on the axis. One `bySeverity` sort-key function (index in `SEVERITIES`)
  feeds both `Legend` and `Tooltip` `itemSorter`. Areas have a 2 px stroke and
  0.7 fill opacity.
- No new unit test: jsdom gives `ResponsiveContainer` zero size, so Recharts
  draws nothing to assert on. The existing dashboard tests (label with total,
  empty and error states) pass unchanged, and the draw order, legend and
  tooltip order were checked in the running app instead.
- Live check (rebuilt frontend, 200 events produced, `/dashboard`): in the
  DOM, the area fills in draw order were `#e53935, #ef6c00, #f9a825, #1e88e5`
  (CRITICAL to INFO, bottom to top), with 0 bar rectangles. The legend read
  INFO, WARNING, MAJOR, CRITICAL. Hovering showed the tooltip "INFO : 114,
  WARNING : 60, MAJOR : 31, CRITICAL : 6", and screenshots in dark and light
  mode showed smooth bands with red CRITICAL on the axis. The theme was set
  back to System afterwards.
- Step 3: a `legendLabel` formatter wraps each legend label in a span with the
  secondary text color. Live, the computed label color was
  `rgba(0, 0, 0, 0.6)` in light mode and `rgba(255, 255, 255, 0.7)` in dark
  mode. The markers kept `#1e88e5, #f9a825, #ef6c00, #e53935` (INFO to
  CRITICAL), and a dark-mode screenshot confirmed gray labels beside colored
  markers. The theme was set back to System afterwards.
- Observed, unchanged: a burst from `/produce` sets the y-axis scale for the
  whole window, so normal minutes look flat next to it.


<!-- blueprint:completion {"schemaVersion":1,"specBytes":6744,"specSha256":"9601b28f0d3dbf50384d77473dab3b972868ce9474fe1057c8bea1d27e77eb4f","branch":"refs/heads/fix/timeline-stacked-area-chart","head":"b9dc6fa91e2a98c0bb97fe967cff1617b28aae4b","baseRef":"refs/heads/master","baseCommit":"b9dc6fa91e2a98c0bb97fe967cff1617b28aae4b","sourceTree":"ccef64c2b8eb89db652a6460a6d220ddae4d13c1","absentOptional":[]} -->
