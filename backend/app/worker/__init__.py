"""Phase 8: server-side OCR/AI extraction worker.

Run via `arq app.worker.worker.WorkerSettings`. Picks up extract_screenshot
jobs from the upload route, calls the vision model, writes a draft row.
"""
