# Android UI Alignment Contract

This contract keeps the Android app aligned with the approved ProofLedger Figma
prototype while preserving native behavior and all existing product contracts.

## Scope

- Five tabs remain: Home, Records, Reports, People, Settings.
- Existing routes, ViewModels, repositories, Room schema, backend APIs, sync,
  proof storage, validation, and offline-first behavior do not change for a
  visual implementation.
- Features that are absent from the prototype remain available and adopt the
  same visual system.
- The app is light-theme only for this cut.

## Foundation

- Interface font: Inter.
- Financial digits: DM Mono.
- Currency symbols: Inter.
- Fonts are bundled with license notices.
- Colors come from the approved Figma palette and centralized theme values.
- Screen gutters: 20dp horizontal, 18dp vertical.
- Panel radius: 16dp.
- Control radius: 14dp.
- Minimum interactive target: 48dp.
- Icons use app-local outlined vectors through `ProofIcons`.

## Shared APIs

- `ProofLedgerDimens` and `ProofLedgerShapes` own layout constants.
- `ProofLedgerTypography` owns the app type scale.
- `ProofIcons` owns navigation and common-action vectors.
- `ProofLedgerScreen`, `PageHeader`, shared panels, fields, buttons, chips,
  dialogs, state views, record rows, and bottom navigation are used before
  creating screen-specific variants.
- `MoneyAmount` owns mixed-font and adaptive-size financial display.

## Reference Screens

Use these checkpoints when evaluating future changes:

1. Home: summary hierarchy, secondary arrears panel, recent records, add action.
2. Records: search, filters, category access, rows, form sections, details,
   proof display, dialogs, and validation.
3. Reports: period controls, large totals, ranked rows, and export actions.
4. People: add action, search, type filters, list rows, forms, and details.
5. Settings: compact grouped rows, account action placement, and supporting
   Phase 13 navigation.

## Acceptance Checks

- No clipped or overlapping text at 360dp, 393-411dp, or 430dp.
- Font scales 1.0 and 1.3 remain usable.
- Large Naira values fit or adapt without losing digits.
- Long names wrap or truncate deliberately without moving adjacent controls.
- Status bars, navigation bars, gesture areas, and keyboards do not cover
  content.
- Every interactive control has at least a 48dp target and an appropriate
  semantic label.
- Loading, empty, error, validation, proof-image, and offline states match the
  shared system.
- Navigation and all existing save/edit/delete/export/backup/sync behavior are
  unchanged.
- Emulator screenshots are compared with the matching Figma frames before a
  visual checkpoint is accepted.

