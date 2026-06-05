# Payment Proof Tracker — Full Build Plan for Claude Code

## 0. Product Summary

Build an Android-native, offline-first payment proof tracker and business ledger for informal and semi-formal small businesses that currently manage payments using WhatsApp screenshots, handwritten notes, memory, and periodic manual tallying.

This product is **not traditional accounting software in v1**. It is a **payment-proof capture, transaction ledger, and business cash-memory tool**.

The app should let a small-business owner or admin:

* Upload or share payment screenshots.
* Extract key payment details using OCR/AI assistance.
* Confirm or edit extracted details.
* Save clean transaction records.
* Preserve original screenshots as proof.
* Track money in, money out, counterparties, and simple balances.
* Search old transactions quickly.
* Generate basic summaries and exports.
* Continue using the app even with poor or no internet.

The core promise:

> Upload or share payment screenshots, and the app turns them into an organized, searchable business ledger.

An even simpler user-facing promise:

> Never lose track of who paid, who you paid, and what money moved.

Longer-term, ProofLedger can expand beyond small-business payment tracking
into a broader **financial proof capture and organization tool**:

> Capture proof now. Organize later. Export when needed.

In this future direction, ProofLedger helps small businesses, self-employed
workers, contractors, sales reps, freelancers, side-hustle workers, informal
sellers, and individuals preserve proof of payments, receipts, and expenses so
they never lose financial evidence again.

The app should still avoid positioning itself as full accounting software or a
tax filing app. It should help users store and organize financial proof for
later review, not make accounting or tax determinations.

---

## 1. Product Category and Positioning

### 1.1 What This Product Is

This product is a:

* Payment proof tracker
* Screenshot-to-ledger tool
* Financial proof capture and organization tool
* Proof vault for money events
* Business money tracker
* Lightweight financial operations assistant
* Searchable memory for small-business transactions

Long-term, "proof" can include receipts, work-related expenses, meals, gas,
supplies, phone bills, subscriptions, travel/mileage, bank or e-transfer proof,
cash payment notes, invoices, payment screenshots, and customer payment
confirmations.

### 1.2 What This Product Is Not in v1

The v1 product should **not** become:

* Full accounting software
* QuickBooks clone
* Tax filing system
* Payroll system
* Inventory management platform
* ERP system
* Bank reconciliation platform
* Invoice-heavy finance suite
* Complex double-entry accounting tool

The app should avoid accounting jargon unless absolutely necessary.

Avoid language like:

* debit
* credit
* journal entry
* general ledger
* receivables
* payables
* reconciliation
* chart of accounts

Use human language:

* money in
* money out
* who paid
* who you paid
* proof
* balance left
* summary
* customer payment
* supplier payment
* subcontractor payment
* expense
* proof stored
* captured for review
* ready for accountant/tax review
* possible tax review category

---

## 2. Initial ICP

### 2.1 First Target User

The first target user is a Nigerian small-business operator, family business owner, contractor, supplier, subcontractor, or admin staff member who receives proof-of-payment screenshots through WhatsApp and currently records transactions manually in a notebook, spreadsheet, or memory-based system.

### 2.2 User Characteristics

The first user is likely:

* Android-first
* mobile-first
* not accounting-trained
* busy and operationally overloaded
* used to WhatsApp and screenshots
* distrustful of complicated software
* sensitive about financial data privacy
* unwilling to spend hours learning new tools
* comfortable with simple mobile workflows
* focused on practical outcomes, not financial theory

### 2.3 First Beachhead Market

Start with construction-adjacent SMEs:

* small contractors
* suppliers
* subcontractors
* material vendors
* family-run construction/service businesses
* businesses that receive many transfer screenshots
* businesses that need to know who has paid and who still owes

The app should remain general underneath, but the first UX language can reflect this market:

* customer payments
* supplier payments
* subcontractor payments
* material purchases
* site expenses
* transport
* wages
* equipment

---

## 3. Product Principles

### 3.1 Principle 1: Minimize User Thinking

Every screen should ask for the least amount of information possible.

The app should never make the user feel like they need to understand accounting to use it.

Required fields should be minimal:

* amount
* direction: money in or money out
* date
* counterparty or unknown

Everything else can be optional.

### 3.2 Principle 2: Fit Existing Behavior

Users already receive screenshots on WhatsApp. The product should fit this behavior instead of forcing a new workflow.

The app should support:

* sharing screenshots from WhatsApp/gallery into the app
* uploading screenshots from gallery
* taking photos of paper receipts/proofs
* manual entry when OCR fails

### 3.3 Principle 3: AI Is Assistant, Not Authority

AI/OCR extraction must create a **draft**, not a confirmed transaction.

The user must confirm or edit before the transaction becomes final.

The app should clearly show uncertainty when confidence is low.

Example:

> We found these details, but some fields may need checking.

### 3.4 Principle 4: Preserve Original Proof

The screenshot is the evidence. The transaction is the structured interpretation.

Always preserve the original screenshot unless the user deletes it.

Every transaction created from a screenshot should link back to the original screenshot.

### 3.5 Principle 5: Offline-First Reliability

The app must continue working when internet is poor.

Users should be able to:

* add transactions offline
* attach screenshots offline
* view locally saved transaction history offline
* queue uploads for later sync
* see clear pending sync states

### 3.6 Principle 6: Trust Through Transparency

For financial data, the app must be transparent.

The user should know:

* what was extracted
* what was confirmed
* what was edited
* what is pending
* what failed
* what is stored
* what can be exported
* what can be deleted

### 3.7 Principle 7: Fast Native Feel

The app should feel instant and native on Android.

Optimize for:

* fast cold start
* fast image selection
* immediate local saving
* background sync
* quick confirmation flow
* low-end Android performance
* minimal battery/network usage

---

## 4. Recommended Tech Stack

## 4.1 Mobile App

Build Android-native first.

Recommended stack:

```text
Kotlin
Jetpack Compose
Room SQLite
WorkManager
CameraX
Google ML Kit Text Recognition
Retrofit or Ktor Client
Firebase Cloud Messaging
Jetpack Security
```

### Why Kotlin + Jetpack Compose

Use Kotlin + Jetpack Compose because the target workflow lives on Android phones.

Native Android gives the best experience for:

* share-sheet integration from WhatsApp/gallery
* camera and gallery access
* offline-first local database
* background sync
* push notifications
* low-end Android performance
* fast startup
* reliable local file handling

### Key Android Components

#### Jetpack Compose

Use for the UI layer.

Benefits:

* modern declarative UI
* faster iteration
* clean state-driven screens
* smooth native UI

#### Room SQLite

Use for local offline storage.

The app should save transactions and screenshots locally first, then sync to cloud.

#### WorkManager

Use for background jobs:

* upload pending screenshots
* sync transactions
* retry failed syncs
* run background OCR tasks
* send pending extraction requests

#### CameraX

Use for camera capture later.

#### Google ML Kit Text Recognition

Use for on-device OCR.

Purpose:

* instant first-pass extraction
* offline OCR attempt
* lower cloud cost
* better UX before server-side extraction completes

#### Retrofit or Ktor Client

Use for API communication.

Either is acceptable. Prefer one and use it consistently.

#### Firebase Cloud Messaging

Use for push notifications:

* extraction complete
* duplicate detected
* weekly summary ready
* sync issue
* expected payment overdue later

#### Jetpack Security

Use for encrypted local sensitive values:

* auth token storage
* secure preferences
* potentially sensitive local metadata

---

## 4.2 Backend

Recommended stack:

```text
FastAPI
Postgres
Redis
Dramatiq or Celery workers
Object storage
OCR/AI extraction service
```

### Why FastAPI

FastAPI is good for this product because:

* Python is strong for OCR/AI pipelines.
* It integrates easily with OpenAI/Gemini/Vision/OCR tools.
* Development is fast.
* It is already familiar from other projects.
* It is sufficient for early and mid-scale API workloads.

Performance bottlenecks are more likely to come from OCR, image upload, and network conditions than FastAPI itself.

### Later Optimization

If scale becomes very large, specific high-volume services can be moved to Go later, such as:

* notification service
* webhook ingestion
* sync event processor
* analytics event processor
* high-throughput ingestion worker

Do not start with Go unless needed.

---

## 4.3 Database

Use Postgres.

Recommended early option:

```text
Supabase Postgres
```

Reasons:

* fast setup
* managed Postgres
* auth support
* object storage support
* row-level security support
* useful admin dashboard

Long-term options:

* Supabase dedicated
* Neon
* AWS RDS
* Google Cloud SQL
* Fly Postgres

The schema should be clean and relational because this is structured financial/business data.

---

## 4.4 Object Storage

Use S3-compatible object storage or Supabase Storage.

Store:

* original screenshots
* compressed preview images
* OCR text output
* extracted JSON metadata

Important rule:

> The original screenshot must be preserved as the source of truth/evidence.

---

## 4.5 Queue / Background Jobs

Use Redis + Dramatiq or Celery.

Recommended:

```text
Redis + Dramatiq
```

Alternative:

```text
Redis + Celery
```

Use workers for:

* server-side OCR
* AI extraction
* duplicate checking
* image compression
* report generation
* notification dispatch
* sync reconciliation jobs later

The API should not block while OCR runs.

Flow:

```text
User uploads screenshot
→ API stores screenshot
→ API returns quickly
→ background worker processes OCR/extraction
→ transaction draft created
→ user/app notified
```

---

## 4.6 AI/OCR Stack

Use layered extraction.

### Layer 1: On-Device OCR

Use Google ML Kit Text Recognition.

Purpose:

* instant first-pass extraction
* offline support
* reduced server cost
* smoother user experience

### Layer 2: Server OCR / Parsing

Backend receives OCR text and/or image.

Server runs:

* normalization
* pattern matching
* provider/bank detection
* structured extraction
* confidence scoring

### Layer 3: Vision Model Fallback

Use OpenAI/Gemini Vision for difficult screenshots.

Use only when:

* on-device OCR confidence is low
* OCR text is incomplete
* image format is unfamiliar
* amount/date/reference cannot be reliably found

### Layer 4: Bank/Provider-Specific Parsers Later

Over time, build templates for frequent providers:

* GTBank
* Access Bank
* Zenith
* UBA
* First Bank
* Opay
* PalmPay
* Moniepoint
* Kuda
* Paystack
* Flutterwave

These templates lower cost and increase accuracy over time.

---

## 4.7 Web Dashboard Later

Mobile is the main product.

Add web dashboard later for:

* owners
* accountants
* exports
* monthly review
* staff management
* reporting

Recommended web stack:

```text
Next.js
Tailwind CSS
shadcn/ui
React Query
```

Do not let the web dashboard delay the Android MVP.

---

## 4.8 Observability and Analytics

Use:

```text
Sentry
PostHog
Structured backend logs
```

Sentry:

* mobile crashes
* backend errors
* worker failures

PostHog:

* product analytics
* onboarding funnel
* feature usage
* retention
* OCR correction behavior

---

## 5. High-Level Architecture

```text
Android App
  ├── Jetpack Compose UI
  ├── Room local database
  ├── Local screenshot storage
  ├── ML Kit local OCR
  ├── WorkManager sync queue
  └── API client
        ↓
FastAPI Backend
  ├── Auth/session validation
  ├── Business/transaction APIs
  ├── Screenshot upload APIs
  ├── Draft confirmation APIs
  ├── Reporting APIs
  └── Queue producer
        ↓
Postgres Database
        ↓
Object Storage
        ↓
Redis Queue
        ↓
OCR/AI Worker
  ├── Image preprocessing
  ├── OCR parsing
  ├── AI extraction fallback
  ├── duplicate detection
  ├── confidence scoring
  └── draft creation
        ↓
Android App receives update/polls status
```

---

## 5.1 Android Layer Boundaries

Future UI polish and redesign are expected, including settings, multiple businesses, tabs, navigation updates, and refined dashboard/transaction flows. Keep the Android infrastructure explicitly separate from the UI so those changes do not require storage, sync, backup, or network rewrites.

Rules:

* Compose screens own layout, visual state, and user interaction.
* Compose screens should read ViewModel state and call ViewModel events.
* Compose screens should not call Room DAOs, Retrofit/API services, WorkManager, file storage, or token storage directly.
* ViewModels coordinate screen state, validation, and repository actions.
* Repositories/services own data behavior, sync, proof backup, auth/session behavior, and file operations.
* Infrastructure classes must not depend on Compose or screen layout.
* If redesigned screens need different display data, add dedicated UI state/models instead of bending database entities around presentation needs.

---

## 6. Core Data Flow

### 6.1 Screenshot Upload Flow

```text
User selects or shares screenshot
→ app saves image locally
→ app creates local Screenshot record with pending status
→ app optionally runs local ML Kit OCR
→ app creates local draft if enough info is found
→ WorkManager uploads screenshot when possible
→ backend stores image in object storage
→ backend creates/updates Screenshot record
→ worker processes OCR/AI extraction
→ backend creates TransactionDraft
→ app receives/polls draft
→ user confirms or edits
→ transaction is saved
```

### 6.2 Manual Transaction Flow

```text
User taps Add Money In or Add Money Out
→ user enters amount, date, counterparty/category optional
→ user optionally attaches screenshot
→ transaction saves locally immediately
→ syncs to backend when online
```

### 6.3 Draft Confirmation Flow

```text
TransactionDraft displayed
→ user reviews extracted fields
→ user edits missing/wrong fields
→ user selects direction/category/counterparty
→ user taps Save Transaction
→ confirmed Transaction created
→ draft marked confirmed
→ screenshot linked to transaction
```

### 6.4 Offline Sync Flow

```text
User creates local item offline
→ item marked pending_sync
→ WorkManager retries when network is available
→ backend accepts item
→ local item receives server_id
→ status becomes synced
```

---

## 7. Database Entities

Use UUIDs for server IDs.

Use local UUIDs on device before sync.

Each syncable object should support:

* local_id
* server_id
* sync_status
* created_at
* updated_at
* deleted_at nullable

---

## 7.1 profiles

Represents user profile information.

Fields:

```sql
profiles (
  id uuid primary key,
  full_name text,
  phone text,
  email text,
  created_at timestamptz default now(),
  updated_at timestamptz default now()
)
```

---

## 7.2 businesses

Represents each business account.

```sql
businesses (
  id uuid primary key,
  owner_id uuid references profiles(id),
  name text not null,
  country text default 'Nigeria',
  currency text default 'NGN',
  business_type text,
  plan text default 'free',
  created_at timestamptz default now(),
  updated_at timestamptz default now()
)
```

---

## 7.3 business_members

Build this early even if v1 UI is owner-only.

```sql
business_members (
  id uuid primary key,
  business_id uuid references businesses(id),
  user_id uuid references profiles(id),
  role text default 'owner',
  created_at timestamptz default now()
)
```

Initial roles:

```text
owner
member
viewer
```

v1 can expose owner-only if needed.

---

## 7.4 screenshots

Stores uploaded payment proof image records.

```sql
screenshots (
  id uuid primary key,
  business_id uuid references businesses(id),
  uploaded_by uuid references profiles(id),
  file_url text,
  preview_url text,
  image_hash text,
  local_file_ref text,
  ocr_text text,
  extraction_status text default 'pending',
  extraction_confidence numeric,
  created_at timestamptz default now(),
  updated_at timestamptz default now(),
  deleted_at timestamptz
)
```

Possible `extraction_status` values:

```text
pending
processing
draft_ready
failed
confirmed
ignored
```

---

## 7.5 transaction_drafts

Stores AI/OCR-extracted draft transaction details before user confirmation.

```sql
transaction_drafts (
  id uuid primary key,
  business_id uuid references businesses(id),
  screenshot_id uuid references screenshots(id),
  extracted_amount numeric,
  extracted_currency text default 'NGN',
  extracted_sender text,
  extracted_receiver text,
  extracted_bank text,
  extracted_reference text,
  extracted_date timestamptz,
  extracted_status text,
  suggested_direction text,
  suggested_counterparty_name text,
  suggested_category text,
  raw_json jsonb,
  confidence numeric,
  user_review_status text default 'pending',
  created_at timestamptz default now(),
  updated_at timestamptz default now()
)
```

Possible `user_review_status` values:

```text
pending
edited
confirmed
rejected
```

---

## 7.6 transactions

Confirmed business transactions.

```sql
transactions (
  id uuid primary key,
  business_id uuid references businesses(id),
  screenshot_id uuid references screenshots(id),
  draft_id uuid references transaction_drafts(id),
  counterparty_id uuid references counterparties(id),
  amount numeric not null,
  currency text default 'NGN',
  direction text not null,
  transaction_type text,
  transaction_date timestamptz,
  bank_name text,
  reference_number text,
  description text,
  status text default 'confirmed',
  source text default 'screenshot',
  created_by uuid references profiles(id),
  created_at timestamptz default now(),
  updated_at timestamptz default now(),
  deleted_at timestamptz
)
```

Allowed direction values:

```text
money_in
money_out
unknown
```

Allowed source values:

```text
manual
screenshot
ocr_draft
import
```

Initial transaction types/categories:

```text
customer_payment
supplier_payment
subcontractor_payment
material_purchase
transport
salary_or_wage
rent
equipment
refund
other_expense
unknown
```

---

## 7.7 counterparties

Represents customers, suppliers, subcontractors, staff, etc.

```sql
counterparties (
  id uuid primary key,
  business_id uuid references businesses(id),
  name text not null,
  normalized_name text,
  type text,
  phone text,
  notes text,
  created_at timestamptz default now(),
  updated_at timestamptz default now(),
  deleted_at timestamptz
)
```

Allowed type values:

```text
customer
supplier
subcontractor
employee
driver
landlord
agent
other
unknown
```

Counterparty matching should be conservative.

Never aggressively auto-merge. Suggest possible matches instead.

---

## 7.8 expected_payments

Tracks money expected from or owed to counterparties.

This can be v2, but design for it.

```sql
expected_payments (
  id uuid primary key,
  business_id uuid references businesses(id),
  counterparty_id uuid references counterparties(id),
  direction text not null,
  amount_expected numeric not null,
  amount_paid numeric default 0,
  due_date date,
  description text,
  status text default 'open',
  created_at timestamptz default now(),
  updated_at timestamptz default now()
)
```

Allowed status values:

```text
open
partially_paid
paid
overdue
cancelled
```

---

## 7.9 audit_logs

Track important financial edits and actions.

```sql
audit_logs (
  id uuid primary key,
  business_id uuid references businesses(id),
  actor_id uuid references profiles(id),
  entity_type text not null,
  entity_id uuid not null,
  action text not null,
  old_values jsonb,
  new_values jsonb,
  created_at timestamptz default now()
)
```

Track:

* transaction created
* transaction edited
* transaction deleted
* screenshot uploaded
* draft confirmed
* draft edited
* duplicate warning ignored
* export generated
* sync conflict resolved

---

## 7.10 sync_events

Useful for debugging offline-first sync behavior.

```sql
sync_events (
  id uuid primary key,
  business_id uuid references businesses(id),
  user_id uuid references profiles(id),
  device_id text,
  entity_type text,
  entity_id uuid,
  sync_status text,
  error_message text,
  created_at timestamptz default now()
)
```

---

## 8. Local Device Data Model

The Android app should have local tables mirroring core cloud entities.

Local tables:

```text
LocalBusiness
LocalTransaction
LocalScreenshot
LocalTransactionDraft
LocalCounterparty
LocalSyncQueueItem
```

Each local entity should include:

```text
local_id
server_id nullable
sync_status
created_at
updated_at
deleted_at nullable
last_sync_attempt_at nullable
sync_error nullable
```

Allowed sync status values:

```text
local_only
pending_upload
pending_update
pending_delete
syncing
synced
failed
conflict
```

---

## 9. Backend API Routes

## 9.1 Auth/Profile

Auth can be handled by Supabase Auth or custom JWT auth.

Core profile routes:

```text
GET /me
PATCH /me
```

---

## 9.2 Business Routes

```text
POST /businesses
GET /businesses
GET /businesses/{business_id}
PATCH /businesses/{business_id}
```

---

## 9.3 Screenshot Routes

```text
POST /businesses/{business_id}/screenshots
GET /businesses/{business_id}/screenshots
GET /screenshots/{screenshot_id}
DELETE /screenshots/{screenshot_id}
GET /screenshots/{screenshot_id}/status
```

Upload route should:

1. Authenticate user.
2. Verify business access.
3. Store image.
4. Create screenshot record.
5. Queue extraction job.
6. Return quickly.

---

## 9.4 Transaction Draft Routes

```text
GET /businesses/{business_id}/transaction-drafts
GET /transaction-drafts/{draft_id}
PATCH /transaction-drafts/{draft_id}
POST /transaction-drafts/{draft_id}/confirm
POST /transaction-drafts/{draft_id}/reject
```

Confirm route should:

1. Validate fields.
2. Create transaction.
3. Link screenshot.
4. Mark draft confirmed.
5. Write audit log.
6. Return confirmed transaction.

---

## 9.5 Transaction Routes

```text
GET /businesses/{business_id}/transactions
POST /businesses/{business_id}/transactions
GET /transactions/{transaction_id}
PATCH /transactions/{transaction_id}
DELETE /transactions/{transaction_id}
```

Query parameters:

```text
start_date
end_date
direction
category
counterparty_id
search
limit
offset
```

---

## 9.6 Counterparty Routes

```text
GET /businesses/{business_id}/counterparties
POST /businesses/{business_id}/counterparties
GET /counterparties/{counterparty_id}
PATCH /counterparties/{counterparty_id}
DELETE /counterparties/{counterparty_id}
POST /counterparties/{counterparty_id}/merge
```

Counterparty merge can be v2.

---

## 9.7 Summary / Reporting Routes

```text
GET /businesses/{business_id}/summary?period=weekly
GET /businesses/{business_id}/summary?period=monthly
GET /businesses/{business_id}/summary?start_date=...&end_date=...
GET /businesses/{business_id}/export.csv
GET /businesses/{business_id}/export.pdf
```

---

## 9.8 Sync Routes

For offline-first sync:

```text
POST /sync/push
GET /sync/pull?business_id=...&since=...
POST /sync/resolve-conflict
```

Keep initial sync simple if possible.

For v1, the Android app can call normal CRUD endpoints and maintain local sync state.

A dedicated sync API may come after manual CRUD sync proves too fragile.

---

## 10. Key Screens

## 10.1 Onboarding Screen

Goal: get user into the app quickly.

Steps:

1. Sign up / login with phone or email.
2. Create business.
3. Choose business type.
4. See a very short intro.

Do not ask for too much setup.

Avoid:

* chart of accounts setup
* tax setup
* complex business profile questions
* bank connection

---

## 10.2 Home Dashboard

The home screen should show immediately useful information.

Example layout:

```text
Today
Money In: ₦850,000
Money Out: ₦320,000
Net: ₦530,000

This Month
Money In: ₦6,400,000
Money Out: ₦4,900,000
Net: ₦1,500,000

Primary actions:
[Add Payment Proof]
[Add Money In]
[Add Money Out]

Recent Activity
₦450,000 received from Adewale Supplies
₦120,000 paid to Musa Transport
₦80,000 paid to Cement Depot
```

---

## 10.3 Add Payment Proof Screen

Inputs:

* select from gallery
* take photo
* paste/import shared image

After image selected:

* show preview
* save locally immediately
* run OCR
* show extraction pending or draft ready

---

## 10.4 Android Share-Sheet Intake

User flow:

```text
User is in WhatsApp or Gallery
→ taps Share
→ selects the app
→ app opens directly to payment proof confirmation flow
```

This is a high-priority workflow.

The app should accept:

* image/jpeg
* image/png
* image/webp if possible

---

## 10.5 Confirmation Screen

This is the most important screen.

Layout:

```text
Screenshot preview

We found:
Amount: ₦450,000
Date: May 23, 2026
From/To: Adewale Supplies
Bank/App: GTBank
Reference: 123456789

Direction:
[Money In] [Money Out]

Counterparty:
Adewale Supplies

Category:
Customer Payment

[Save Transaction]
[Edit Details]
```

Rules:

* User can save with minimal required fields.
* Low-confidence fields should be highlighted.
* Missing fields should be optional where possible.
* Show original screenshot nearby.

---

## 10.6 Manual Transaction Screen

Two primary modes:

```text
Add Money In
Add Money Out
```

Fields:

* amount required
* date default today
* counterparty optional
* category optional
* note optional
* attach screenshot optional

---

## 10.7 Transaction List

Features:

* list transactions newest first
* search by name, amount, bank, reference, notes
* filter by money in/out
* filter by date range
* filter by category
* tap transaction to view details and screenshot

---

## 10.8 Transaction Detail Screen

Show:

* amount
* direction
* date
* counterparty
* category
* bank/provider
* reference number
* note
* linked screenshot
* edit history later

Actions:

* edit
* delete
* view screenshot
* share/export receipt summary later

---

## 10.9 Counterparty List

Show:

```text
Adewale Supplies
Total received: ₦2,400,000
Total paid: ₦0
Last transaction: May 23

Musa Transport
Total paid: ₦650,000
Last transaction: May 20
```

Features:

* search counterparty
* view counterparty details
* see all related transactions
* merge duplicates later

---

## 10.10 Summary / Reports Screen

Show:

* this week totals
* this month totals
* money in
* money out
* net movement
* top counterparties
* top categories

Actions:

* export CSV
* export PDF

---

## 11. OCR / Extraction Specification

## 11.1 Expected Extraction Fields

The extraction system should attempt to identify:

```json
{
  "amount": 450000,
  "currency": "NGN",
  "sender_name": "Adewale Supplies Ltd",
  "receiver_name": "ABC Construction",
  "bank_or_provider": "GTBank",
  "transaction_date": "2026-05-23",
  "reference_number": "TRF92839201",
  "status": "successful",
  "suggested_direction": "money_in",
  "confidence": 0.91
}
```

## 11.2 Confidence Scoring

Confidence should consider:

* amount found clearly
* date found clearly
* transaction reference found
* sender/receiver found
* successful status found
* known provider/bank pattern matched
* OCR quality

If confidence is low, UI should say:

> Please check the details. We are not fully sure.

## 11.3 Extraction Output Rules

* Do not hallucinate missing values.
* Return null if a field is not visible.
* Preserve raw OCR text.
* Preserve raw extraction JSON.
* Always require user confirmation before creating final transaction.

## 11.4 Duplicate Detection

Duplicate detection should check:

* exact image hash match
* same reference number
* same amount + date + counterparty
* same uploaded image metadata

If duplicate suspected:

> This looks like a payment you already saved. Save anyway?

Do not block the user completely. Allow save anyway.

---

## 12. Offline-First Requirements

## 12.1 Offline Transaction Creation

When offline:

```text
User creates transaction
→ transaction saves locally
→ status: pending sync
→ app shows transaction immediately
→ WorkManager syncs later
```

## 12.2 Offline Screenshot Upload

When offline:

```text
User attaches screenshot
→ image saved locally
→ screenshot record marked pending upload
→ local OCR can run
→ cloud OCR waits until sync
```

## 12.3 Sync Status UI

Show clear states:

```text
Saved on this phone
Syncing...
Synced
Failed to sync
Will retry
```

Do not make users guess whether data was saved.

## 12.4 Conflict Handling

For v1, use last-write-wins for simple fields, but keep audit logs on backend.

If conflict is serious, mark as conflict and ask user later.

Do not overbuild conflict resolution in v1.

---

## 13. Security and Privacy Requirements

## 13.1 User Trust Messaging

The app should clearly communicate:

* No bank connection required in v1.
* The app does not move money.
* The app only organizes records and screenshots.
* The user controls what they upload.
* Data can be exported.
* Data can be deleted.

## 13.2 Security Basics

Implement:

* HTTPS everywhere
* authenticated API requests
* role-based access checks
* signed URLs for private screenshots
* object storage access restrictions
* encrypted token storage on device
* server-side validation on every write
* audit logs for financial edits

### Operational hygiene (Phase 12 / deployment-time)

* **Signed-URL log scrubbing.** Proof signed URLs include the HMAC
  `signature` in the query string, which means reverse-proxy, CDN, and
  Sentry logs capture working URLs for the link's lifetime (default 15 min).
  Configure log scrubbing rules (drop `signature` and `expires` params) at
  the proxy/CDN tier before going live, and keep the TTL short. A web
  client should prefer posting the signature in a header instead of the
  query string when one ships.
* **Slow-body / Slowloris protection.** The upload route streams the body
  with no per-connection timeout — that's a reverse-proxy concern (nginx
  `client_body_timeout`, Cloud Run / ALB equivalents), not in the app. Make
  sure the deploy story includes it.

## 13.3 Access Control

Every backend route must verify:

* user is authenticated
* user belongs to the business
* user has permission for the action

Do not trust client-provided business IDs without verification.

---

## 14. Monetization Design Hooks

The base product may be free to encourage adoption.

Do not implement full payments in v1 unless needed, but prepare the schema.

Business plan values:

```text
free
starter
pro
business
```

Potential free tier:

* 50 uploads/month
* 1 business
* 1 user
* basic ledger
* basic dashboard
* basic search

Potential paid tier:

* unlimited uploads
* PDF/CSV exports
* multiple users
* counterparty balances
* expected payments
* advanced reports
* WhatsApp reminders later

Need fields for:

* plan
* monthly_upload_count
* feature flags later

Do not block the MVP around monetization.

---

## 15. Analytics Events

Track product validation events.

Important events:

```text
account_created
business_created
manual_transaction_created
screenshot_uploaded
local_ocr_completed
server_ocr_completed
ai_extraction_completed
ocr_failed
draft_created
draft_confirmed
draft_edited
draft_rejected
transaction_saved
transaction_edited
transaction_deleted
transaction_searched
counterparty_created
export_created
duplicate_detected
duplicate_warning_ignored
sync_failed
sync_completed
app_opened
weekly_active_user
```

Main questions analytics should answer:

* Are users uploading screenshots?
* Are they confirming OCR drafts?
* How often do they edit extracted values?
* Which fields fail most often?
* Do users search old payments?
* Do users return weekly?
* Do users export summaries?
* How many transactions are created manually vs OCR-assisted?

---

## 16. Testing Dataset

Before or during OCR implementation, collect 50–100 anonymized payment screenshots.

Include:

* clear screenshots
* blurry screenshots
* cropped screenshots
* duplicate screenshots
* bank app screenshots
* Opay/PalmPay/Moniepoint receipts
* Paystack/Flutterwave receipts
* WhatsApp-forwarded proof images
* paper receipt photos if relevant

For each screenshot, create expected JSON:

```json
{
  "amount": 250000,
  "currency": "NGN",
  "sender": "Adewale Musa",
  "receiver": "XYZ Construction",
  "date": "2026-05-23",
  "reference": "1234567890",
  "bank_or_provider": "GTBank",
  "status": "successful"
}
```

Use this as the extraction benchmark.

---

## 17. Error States to Build

The app must handle non-happy paths.

Required error states:

```text
No internet
Upload failed
Image too large
Image too blurry
OCR failed
Extraction pending
Extraction failed
Duplicate suspected
Missing required amount
Invalid amount
Sync pending
Sync failed
Backend unavailable
Session expired
Permission denied
Screenshot missing locally
```

UX rule:

The user should never lose data because of an error.

If cloud fails, local save should still happen.

---

## 18. Performance Requirements

Optimize for low-end and mid-range Android devices.

Requirements:

* save local transaction immediately
* compress image previews
* lazy-load transaction lists
* avoid heavy animations
* avoid blocking UI during OCR/upload
* use pagination for transaction history
* use local cache for dashboard totals
* retry uploads in background
* reduce large API payloads
* avoid unnecessary polling

The app should feel fast even when OCR is still processing.

---

## 19. Build Phases / Chunks for Claude Code

Build incrementally. Do not attempt the full app in one pass.

---

# Phase 1: Project Foundation

## Goal

Set up the Android and backend foundations with clean architecture, auth-ready structure, local storage, and basic navigation.

## Android Tasks

* Create Kotlin Android project.
* Set up Jetpack Compose.
* Set up navigation structure.
* Set up Room database.
* Set up dependency injection if needed.
* Set up basic local entities.
* Set up API client shell.
* Set up secure token storage placeholder.
* Set up theme and design system.

## Backend Tasks

* Create FastAPI project.
* Set up project structure.
* Set up Postgres connection.
* Set up Alembic migrations.
* Create initial database schema.
* Add basic health check route.
* Add auth middleware placeholder.
* Add business/profile models.

## Deliverables

* App launches.
* Basic navigation works.
* Local Room database works.
* Backend starts.
* Database migrations run.
* Health check works.

---

# Phase 2: Auth and Business Setup

## Goal

Allow user to create or access a profile and create a business workspace.

## Android Tasks

* Build login/signup placeholder or real auth integration.
* Build create business screen.
* Store selected business locally.
* Show empty dashboard after business creation.

## Backend Tasks

* Implement profile routes.
* Implement business routes.
* Implement business member creation.
* Enforce access checks.

## Deliverables

* User can create/select a business.
* Business is stored locally and remotely.
* Dashboard loads for selected business.

---

# Phase 3: Manual Ledger MVP

## Goal

Build the simplest useful product before OCR.

## Android Tasks

* Build Add Money In screen.
* Build Add Money Out screen.
* Save transaction locally.
* Sync transaction to backend.
* Build transaction list.
* Build transaction detail screen.
* Build dashboard totals: today/month money in/out/net.
* Add basic search/filter locally.

## Backend Tasks

* Implement transaction CRUD.
* Implement transaction list filters.
* Implement summary endpoint.

> Note: audit logging for transaction create/edit/delete is intentionally
> deferred to Phase 10. Logging events before the workflow shape settles
> would lock us into an event schema we may have to migrate later. Once
> manual ledger + counterparties stabilize and sync-conflict semantics are
> being designed in Phase 10, audit records can be added against the real
> finished workflows.

## Deliverables

* User can manually add transactions.
* User can see totals.
* User can search/filter transaction history.
* Transactions sync to backend.

---

# Phase 4: Counterparties

Status: implemented.

## Goal

Add customers/suppliers/subcontractors so the app becomes useful for relationship-based tracking.

## Phase 4 Decisions

* Keep `counterparty_name` alongside nullable `counterparty_id` so existing rows remain safe and transactions keep a display snapshot.
* Counterparty selection stays optional in v1. Users can add one inline from the transaction form, but free-text entry still works.
* Counterparty types are: customer, supplier, subcontractor, employee, driver, landlord, agent, other, unknown.
* Counterparty merge, search escaping, audit logs, dependency-ordered sync, and currency picking remain deferred to later phases.

## Android Tasks

* Add counterparty field to transaction form.
* Allow creating counterparty inline.
* Build counterparty list.
* Build counterparty detail screen with related transactions.
* Suggest existing counterparties by name.

## Backend Tasks

* Implement counterparty CRUD.
* Link transactions to counterparties.
* Add counterparty summary query.

## Deliverables

* User can track transactions by person/business.
* User can see transaction history for a counterparty.

---

# Phase 5: Screenshot Attachment

## Goal

Allow users to attach payment proof screenshots to transactions.

## Android Tasks

* Add image picker from gallery.
* Add screenshot preview.
* Save image locally.
* Attach image to manual transaction.
* Upload image in background.
* Show sync state.
* Open full screenshot from transaction detail.

## Backend Tasks

* Implement screenshot upload route.
* Store image in object storage.
* Create screenshots table records.
* Link screenshot to transaction.
* Add signed/private access URLs.

## Phase 5 Hardening Notes

These were applied during the Phase 5 build after an adversarial review and
do not need to be re-done:

* Image allowlist (`image/jpeg`, `image/png`, `image/webp`) + magic-byte sniff
  on the uploaded bytes. Explicitly refuses `image/svg+xml` so a future web
  dashboard rendering proofs in `<img>` tags doesn't inherit a stored-XSS
  sink, and refuses payloads whose bytes don't match the declared mime so the
  proof store can't be used to host phishing-friendly executables behind
  legitimate-looking signed URLs.
* Pixel-dimension clamp (`[0, 100_000]`) on screenshot `width` / `height`
  fields before they hit the Postgres INTEGER column.
* Text-length caps on every free-text field across transactions, counterparties,
  and profile schemas so an authenticated user can't store megabytes of notes.
* Per-amount cap on the Android amount parser at ₦999,999,999,999 mirroring
  `MAX_AMOUNT_MINOR` on the backend, so `whole * 100` can't silently overflow
  past the viewmodel's `<= 0` check.
* WorkManager default initializer disabled in `AndroidManifest.xml` so
  `Configuration.Provider` + Hilt actually wins.
* Streaming Content-Length cap on uploads so a 100 MB POST is refused before
  the body is buffered.
* Storage path traversal hardening (regex allowlist + `..` rejection).
* Orphan-file cleanup on commit failure (but not on idempotency races, since
  the winning row references the same key).
* Signed-URL HMAC secret refuses to fall back to a dev string outside
  `environment=development`.

## Deliverables

* User can create transaction with screenshot proof.
* Original screenshot is preserved.
* Screenshot syncs to backend.

---

# Phase 6: Android Share-Sheet Intake

## Goal

Let users share payment screenshots from WhatsApp/gallery directly into the app.

## Android Tasks

* Add intent filter for image sharing.
* Receive shared image URI.
* Copy image to app local storage.
* Open Add Payment Proof flow automatically.
* Create pending screenshot record.
* Let user create/confirm transaction from shared image.

## Backend Tasks

* No major new backend tasks if screenshot upload already works.

## Deliverables

* From WhatsApp/gallery, user can tap Share → app → save payment proof.

---

# Phase 7: Local OCR Drafts

## Goal

Use on-device OCR to reduce manual entry.

## Android Tasks

* Integrate Google ML Kit Text Recognition.
* Run OCR after image selection/share.
* Store raw OCR text locally.
* Parse amount/date/reference where possible using local heuristics.
* Create local TransactionDraft.
* Build confirmation screen.
* Allow user to edit and save confirmed transaction.

## Backend Tasks

* Add transaction_drafts schema if not already done.
* Add draft confirm route.
* Store OCR text with screenshot.

## Deliverables

* User uploads screenshot.
* App extracts visible text locally.
* User sees editable draft.
* User confirms transaction.

---

# Phase 8: Server-Side OCR/AI Extraction

Status: implemented, including Phase 8.1 eager upload on attach.

## Goal

Improve extraction accuracy with backend workers and optional vision model fallback.

## Android Tasks

* Upload OCR text with screenshot.
* Show extraction status: processing/draft ready/failed.
* Poll or receive update when server draft is ready.
* Merge/update local draft with server result when available.

## Backend Tasks

* Add Redis queue.
* Add OCR/AI worker.
* Process uploaded screenshot.
* Parse structured fields.
* Add confidence scoring.
* Create transaction draft.
* Add failure handling.
* Add raw_json storage.

## Deliverables

* Backend creates stronger drafts from screenshots.
* Low-confidence extraction is flagged.
* User confirms before final save.

## Phase 8.1 Implementation Notes

Phase 8.1 closes the create-flow UX gap from the first Phase 8 build:
proofs can now upload as orphan screenshots before a transaction exists, then
the Android form polls for the server draft while the user is still creating
the record.

Implemented behavior:

* `screenshots.transaction_id` is nullable so the backend can store orphan
  proofs until the user saves the record.
* `POST /businesses/{business_id}/screenshots` accepts uploads without
  `transaction_id` and enqueues extraction as usual.
* `TransactionFormViewModel` eagerly uploads the selected/shared proof,
  polls `GET /screenshots/{id}/draft`, and merges ready fields through the
  same "do not clobber user edits" ownership logic.
* On save, `screenshot_id` links the orphan atomically inside the transaction
  create/update request.
* A daily worker purges old orphan screenshots after 7 days.
* Android accepts nullable `transaction_id` in `ScreenshotDto`, fixing the
  "Draft unavailable" parse failure for successful orphan uploads.
* Amount fields now display comma-separated numbers for both user-typed
  amounts and AI-filled amounts.

Known limitation: if orphan upload succeeds while online but the transaction
save later fails offline, the local pending transaction does not persist the
orphan-link intent. The orphan will be cleaned by the 7-day purge. This is a
documented trade-off for keeping Phase 8.1 schema-light on Android.

---

# Phase 9: Duplicate Detection

Status: implemented.

## Goal

Warn before accidental duplicate transaction records without blocking save.

## Android Tasks

* Store nullable proof image SHA-256 on local screenshot rows (Room v9).
* Check local duplicate candidates before save so warnings work offline.
* Best-effort backend check when online; backend failure never blocks save.
* Show inline warning in the transaction form with "Review fields" and
  "Save anyway."
* Reuse the same flow for AI-drafted and manually typed records.

## Backend Tasks

* Store nullable `screenshots.image_sha256` from validated upload bytes.
* Add `POST /businesses/{business_id}/transactions/duplicate-candidates`.
* Check same proof hash, normalized reference number, and exact
  amount/direction/date/counterparty matches.
* Exclude soft-deleted rows, edit self-matches, and cross-business data.
* Audit-log "duplicate warning ignored" is deferred to Phase 10 audit logging.

## Deliverables

* User gets warned when a saved record looks duplicated, then can review or
  save anyway.

## Verification

* `uv run pytest`
* `uv run alembic upgrade head`
* `.\gradlew.bat :app:testDebugUnitTest`
* `.\gradlew.bat :app:connectedDebugAndroidTest`
* `.\gradlew.bat :app:assembleDebug`

---

# Phase 10: Offline Sync Hardening

## Goal

Make offline-first behavior reliable.

## Android Tasks

* Improve sync queue.
* Retry failed uploads/transactions.
* Show sync status clearly.
* Add local-first dashboard updates.
* Add failed sync screen/list.
* Handle auth/session expiration gracefully.
* Coalesce client-side sync events before hitting the server. A queued
  `create X` followed by `delete X` should collapse to nothing locally
  rather than POSTing a create the server will later 404 because the row
  is soft-deleted. Treat any 404 on a queued retry as terminal (stop
  retrying) instead of looping forever.

## Backend Tasks

* Make CRUD endpoints idempotent where needed. Specifically: wrap
  `db.commit()` on the **transactions** and **counterparties** `POST`
  handlers in `try/except IntegrityError` so a client retry that races the
  original insert returns the existing row (mirror Phase 5's screenshot
  route pattern) instead of bubbling a 500. The screenshot route already
  handles this; transactions and counterparties do not.
* Support client-generated IDs if helpful.
* Add sync event logs.

  **Status: implemented on-device only; server-side table deferred to
  Phase 12.** Sync attempts are logged locally on Android
  (`SyncEventLogger` → `LocalSyncEventEntity`), which is the robust place
  for the common failure mode: a device with no connectivity can't report a
  network failure *over* the network it lacks, so a local log is the only
  one guaranteed writable offline. A server-side `sync_events` table (spec
  §7.10) is a fleet-scale observability feature — useful for "show all
  failed syncs across users this week" dashboards — and naturally belongs
  with the Phase 12 analytics/structured-logging work, not Phase 10. The
  Phase 10 deliverables ("data syncs reliably; user never loses local data")
  are met by the sync machinery itself; server-side sync logging was only
  ever a debugging aid. Accepted trade-off until Phase 12: sync failures
  can't be inspected centrally — only from a user's device or report.
* Add conflict handling basics.
* Add audit log table + write create/edit/delete events for transactions
  (and any other entities that exist by this phase). Deferred here from
  Phase 3 so the event shape matches the stabilized sync-aware workflows.

  **Status: implemented for transactions and counterparties.** The table is
  `audit_events` (migrations `0009_audit_events` + `0010_audit_event_values`).
  Each row carries `action` (create/update/delete/duplicate_check/
  duplicate_warning_ignored), `status`, an optional `error_message`, and
  field-level `old_values`/`new_values` JSON diffs:
  - create → `new_values` holds the created snapshot, `old_values` null;
  - update → only the *changed* user-facing fields, before in `old_values`
    and after in `new_values` (null/null when nothing material changed);
  - delete → `old_values` holds the pre-delete snapshot, `new_values` null.
  Audited fields are the user-facing content only (amounts, direction, dates,
  counterparty, category, note, bank, reference for transactions; name/type/
  phone/notes for counterparties) — not server bookkeeping. The diff columns
  exist specifically so the Phase 12 edit-history UI can render *what*
  changed, not just that an edit happened. UUID/date values are stringified
  before storage by `app/services/audit.py`. No read API yet — events are
  write-only until the Phase 12 history UI consumes them.
* Add a `transaction_id` filter to `GET /businesses/{id}/screenshots` so a
  syncing client (or future web dashboard) can pull just the proofs tied to
  one transaction instead of paging the whole business. Deferred from
  Phase 5: nothing called the list endpoint with that need yet, but cross-
  device sync will. Cheap to add — just a `WHERE transaction_id = ?` clause.
* Replace `limit`/`offset` pagination on the screenshots list with cursor
  pagination keyed on `(updated_at, id)` — e.g. `?since=<updated_at>&
  after_id=<uuid>`. `offset` pagination silently repeats or skips rows when
  new uploads land between pages, which is exactly the regime delta sync
  operates in. Apply the same pattern to transactions/counterparties lists
  if they grow large enough to matter.

  **Status: implemented for screenshots.** The endpoint still accepts
  `limit`/`offset` for compatibility, but clients can now use
  `since=<updated_at>&after_id=<uuid>` for stable cursor paging ordered by
  `(updated_at DESC, id DESC)`. The implementation uses a SQLite-safe
  timestamp comparison path in tests. Transaction/counterparty cursor
  pagination remains optional until true delta sync needs it.

  **Historical note:** An attempt in the Phase 10
  later-items slice was reverted before commit because the cursor
  comparison hit a SQLAlchemy + SQLite portability problem:

  - `created_at` columns use `DateTime(timezone=True)` with
    `server_default=func.now()`. In SQLite that resolves to
    `CURRENT_TIMESTAMP`, which is **second-precision** — values are stored
    as e.g. `'2026-05-29 03:48:18'`.
  - When SQLAlchemy binds a Python `datetime` (microsecond=0) for the
    comparison, the SQLite dialect formats it with microseconds:
    `'2026-05-29 03:48:18.000000'`.
  - The two strings are unequal in SQLite text comparison. Worse, the
    DB-stored form is *lexicographically smaller* than the bound form, so
    `created_at < cur_created_at` matches **every** row with the same
    second — pagination loops on the same page forever. Verified with a
    standalone SQLAlchemy repro: a roundtripped datetime fails `==` and
    spuriously succeeds `<` against itself.
  - Postgres production has no such mismatch (native timestamp type,
    microsecond precision both stored and bound), so the bug is
    test-only — but the test environment is the only contract we can
    enforce in CI today.

  Options for the real implementation when we pick this back up:

  1. **Dialect-specific text casting.** Use SQLAlchemy `func.strftime` on
     SQLite and `func.to_char` on Postgres to compare against a uniform
     text format that matches the stored value. Adds a dialect branch.
  2. **Custom `DateTime` type with fixed storage format.** Configure the
     SQLite side with `storage_format='%Y-%m-%d %H:%M:%S'` (no
     microseconds) so bound and stored values share a format. Risk: any
     code path that currently relies on microsecond precision in tests
     would silently truncate. Requires an audit.
  3. **Switch the test backend to Postgres.** Run tests against a
     Postgres container (already in `docker-compose.yml`). Eliminates the
     SQLite mismatch entirely but slows the suite and complicates CI.
  4. **Cursor on `id` only with a stable secondary order.** Change the
     ORDER BY to `id DESC` and cursor on `id`. Loses chronological "newest
     first" UX but is dialect-agnostic. Acceptable for delta-sync polling
     where order doesn't matter to the consumer; not acceptable for
     user-facing list UIs.

  Recommended path when delta sync is on the roadmap: option 1 (dialect
  branch behind a small helper). Keep the offset endpoints alongside the
  new cursor params during the transition so existing callers don't
  break. The `transaction_id` filter on the screenshot list is already
  in place (committed alongside conflict handling) and works orthogonally
  to whatever pagination scheme replaces offset.

## Deliverables

* App remains usable offline.
* Data syncs reliably later.
* User never loses local data.
* Audit log records exist for every financial create/edit/delete.

---

# Phase 11: Reports and Exports

## Goal

Give users trust and portability through summaries and exports.

## Android Tasks

* Build summary screen.
* Add date range selector.
* Add CSV export action.
* Add PDF export action.
* Let user share/export generated files.

## Backend Tasks

* Implement summary endpoint.
* Implement CSV export.
* Implement PDF report generation.
* Track export events.

## Deliverables

* User can generate monthly/weekly summaries.
* User can export records for review/accountant/disputes.

## Phase 11 implementation notes

**Status: complete.** Android summary, offline CSV export, online backend PDF
export, Android PDF sharing, backend audit rows, and Phase 11 tests have
shipped.

Decisions locked in during planning:

* **PDF: backend-only (online).** PDF is rendered server-side through
  `GET /businesses/{business_id}/export.pdf` and shared by Android via the
  same FileProvider flow as CSV. CSV remains available offline.
* **Summary screen + offline CSV.** Both are computed/generated on-device from
  Room, so summaries and CSV export work with no connectivity.
* **No backend `/summary` or `/export.csv` for Android.** The §9.7 backend
  `/summary` and `/export.csv` endpoints are only needed by a future web
  dashboard; Android satisfies those deliverables locally.
* **Export tracking is split by surface.** Android logs `export_created` via
  the existing on-device `SyncEventLogger` (entity_type `export`), consistent
  with the Phase 10 decision to keep sync/event logging on-device until the
  Phase 12 server-side `sync_events` work. Backend PDF export also writes an
  `audit_events` row with the export format, date range, and row count.
* **Periods are whole weeks or whole months only** — no free-form custom
  start/end ranges. The user picks a unit (Month/Week) and can navigate to
  *any* one of them: a ◀ ▶ stepper for adjacent periods (next is disabled
  past today) plus a tap-to-jump dropdown listing periods that contain
  records (current period always included). Defaults to the current month.
* **CSV amount column = major-unit decimal** (e.g. `1250.00`) for
  accountant/spreadsheet readability; directions render in plain language
  ("Money in"/"Money out"); RFC-4180 quoting on free-text fields.
* **Navigation:** a "Reports & export" entry on the Home dashboard opens a
  dedicated full screen (not a popup) where the user picks the period, sees
  totals (money in/out/net, top people, top categories), and taps Export CSV
  or Export PDF -> Android share sheet. PDF failures show "Connect to the
  internet to generate a PDF"; CSV stays offline-first.

Key files (Android):

* `ui/format/ReportPeriod.kt` — whole week/month period model + stepping +
  `periodsCovering` jump-list + `relativeLabel`.
* `data/repository/ReportRepository.kt` — pure `summarize` + `buildCsv`;
  `exportCsv` runs file IO on `Dispatchers.IO`; `exportPdf` streams backend
  bytes and writes them through the export writer.
* `data/export/ExportFileWriter.kt` — interface + `AndroidExportFileWriter`
  (cacheDir/exports + FileProvider URI), mirroring `ProofFileStore`.
* `ui/viewmodel/SummaryViewModel.kt`, `ui/screens/SummaryScreen.kt`.
* Wiring: `Route.Summary` + `AppNavHost`, dashboard entry point,
  `AndroidManifest.xml` FileProvider + `res/xml/file_paths.xml`,
  `di/ProofModule.kt` writer binding.
* Tests: `ReportRepositoryTest`, `ReportPeriodTest`.

Key files (backend):

* `api/routes/reports.py` — `GET /businesses/{business_id}/export.pdf`, member
  authorization, date validation, proof counts, and export audit row.
* `services/report_pdf.py` — HTML/PDF rendering and report insight helpers.
* Wiring: `app/main.py` router registration.
* Tests: `tests/test_phase11.py`.

Verification on 2026-06-04: `:app:testDebugUnitTest`, `:app:assembleDebug`,
and `uv run pytest tests/test_phase11.py` pass.

---

# Phase 12: Polish, Analytics, and Reliability

## Goal

Improve product quality, trust, and validation measurement.

## Android Tasks

* Add Sentry.
* Add PostHog or analytics client.
* Improve loading states.
* Improve empty states.
* Improve error states.
* Add onboarding tips.
* Improve primary navigation/information architecture so important workflow
  destinations are not hidden as small Home text links:
  * Move/promote **Manage categories** into the Records area as a real
    record-organization action (for example, a prominent secondary action near
    filters or a Records header action/overflow item). It should feel tied to
    organizing records, not like a minor dashboard link.
  * Promote **Reports & export**. Decide in this phase whether it should become
    a top-level Reports tab (preferred if five bottom-nav items remain usable:
    Home, Records, Reports, People, Settings) or a prominent Home dashboard
    card/action with a secondary Records entry point. In either case, remove
    the current tiny-link treatment.
* Optimize cold start and transaction list performance.
* Add image compression.
* Continue low-end-device responsiveness testing. A small audit has already
  moved proof file copy/compression/deletion and share-sheet intake copying
  off the UI thread, and made dashboard sync indicators clear reliably after
  sync failures.
* Add user-visible edit history UI for transactions, backed by the audit
  log written in Phase 10. The `audit_events` rows already carry field-level
  `old_values`/`new_values` diffs for transactions and counterparties, so
  this UI can render *what* changed per edit — it needs a read endpoint
  (none exists yet; events are write-only today) and the Compose screen.
* Escape `%` and `_` in search input before building `LIKE` patterns on
  both Room (Android) and SQLAlchemy (backend) so a user searching for a
  literal `%` or `_` does not turn it into a SQL wildcard. Low priority —
  Nigerian counterparty names rarely contain these — but worth fixing
  with the rest of the polish pass.

## Backend Tasks

* Add structured logging.
* Add Sentry backend.
* Add analytics event ingestion if needed.
* Add the server-side `sync_events` table + ingestion (spec §7.10), deferred
  here from Phase 10. Devices already log sync attempts locally
  (`SyncEventLogger`); this adds a way for them to report those attempts up
  so failed syncs can be inspected centrally across users (a fleet-scale
  observability feature that belongs with the rest of the analytics work).
  Devices must queue reports and flush them when connectivity returns, since
  the most common failure being reported is "no connectivity."
* Add rate limiting. The Phase 5 5 MB upload cap blocks single-request abuse,
  but an authenticated user can still loop POSTs and fill object storage.
  Per-user-per-route limits (e.g. ≤ N proof uploads per minute) close that.
* Add better validation. Specifically:
  * Validate `currency` against an ISO 4217 allowlist (currently any string
    sticks, so a malicious PATCH like `{"currency": "<script>"}` survives
    until a web dashboard renders it raw).
  * Enforce server-side string-length caps on every text column that
    accepts free input. Phase 5 added Pydantic `max_length` caps to all
    schemas under `app/schemas/`; the audit-log table (Phase 10) and any
    later entities must match.
* Add CORS configuration once the web dashboard (§4.7) is live. Only the
  dashboard origin should be allowed — never `*`. Bearer-token auth means
  CSRF is not an issue, but cross-origin reads on signed-URL responses still
  need the right `Access-Control-Allow-Origin` headers to render.
* Add backups/retention policy planning.

## Deliverables

* App is testable with real users.
* Analytics reveal adoption and OCR quality.
* Error handling is production-ready enough for pilot usage.

---

## 20. Later Phases / v2+

Do not build these in v1 unless specifically required.

## 20.0 Financial Proof Vault Direction

ProofLedger can become a broader **proof vault for money events**.

Core idea:

> Any time money moves and the user may need proof later, ProofLedger should
> help them capture it, categorize it, store the original proof, and retrieve or
> export it when needed.

This is a future product direction, not an instruction to implement these
capabilities immediately.

### Positioning

Describe ProofLedger as:

* a financial proof capture and organization tool
* a proof vault for payments, receipts, and expenses
* a simple way to preserve financial evidence

Do **not** describe it as:

* full accounting software
* a tax filing app
* a tool that decides whether expenses are deductible

Avoid saying:

* "This expense is deductible."

Prefer language like:

* "Captured for review"
* "Possible tax review category"
* "Ready for accountant/tax review"
* "Proof stored"

The UX should feel like:

> Take a picture. Add a label. Done.

### Broader Workspace Concept

Long-term, a workspace should mean an income activity or financial proof
folder, not only a formal business account.

Examples:

* small business
* side hustle
* self-employed work
* sales/commission work
* contracting/subcontracting
* tutoring/freelancing
* personal tax receipts
* a seasonal job, such as "Sales Rep Summer 2024"

Future onboarding can feel less corporate by asking:

> What are you tracking?

Possible choices:

* Small business
* Side hustle
* Self-employed work
* Sales/commission work
* Personal tax receipts
* Other

### Future Capture Types

Eventually, the app can capture:

* receipt photo
* payment screenshot
* invoice
* bank transfer or e-transfer proof
* cash payment note
* mileage/travel proof
* customer payment confirmation

Eventually, extraction/storage can include:

* date
* vendor/customer
* amount
* tax/HST if visible
* category
* workspace
* payment method
* notes
* original proof image

### Future Review and Export

Month-by-month views can eventually show:

* total revenue received
* total expenses
* possible tax-review categories
* missing proof warnings
* receipt archive
* export for accountant/tax review

Important example use case:

A self-employed sales rep or contractor may have work-related expenses they
want to track for accountant or tax review. They may lose physical receipts, so
ProofLedger should let them take a picture immediately, store the original
receipt, extract the amount/date/vendor later, and organize everything month by
month.

### Guardrails

* Do not claim an expense is deductible.
* Use "tax review" or "possible deduction category" language instead.
* Preserve original proof images.
* Keep offline-first behavior.
* Keep the app simple enough for non-technical users.
* Avoid becoming bloated accounting software.
* Keep the core promise simple: "Capture proof now. Organize later. Export when needed."

## 20.1 Expected Payments / Balances

Add ability to track:

* money expected from customers
* money owed to suppliers/subcontractors
* partial payments
* overdue balances

## 20.2 WhatsApp Business Bot

Later workflow:

```text
User forwards payment proof to WhatsApp bot
→ bot extracts details
→ bot replies with confirmation
→ transaction saved
```

Do this after Android share-sheet workflow is validated.

## 20.3 Multi-User Team Support

Add:

* invite staff
* roles
* viewer/accountant access
* audit history UI

## 20.4 Bank Integrations

Do not build in v1.

Later, allow bank feeds where possible.

But bank integrations may increase user fear and compliance complexity.

## 20.5 AI Business Assistant

Once data is structured, add natural language questions:

* How much did we collect this week?
* Who still owes us?
* How much did we pay suppliers this month?
* Show all payments from Adewale.
* Generate a report for my accountant.

Do not build before the ledger data is clean.

## 20.6 Inventory / Project Tracking

For construction-adjacent businesses, later add:

* site/project tags
* material cost tracking
* supplier spend by project
* subcontractor payments by job

## 20.7 Encrypted Local Database

§4.1 specifies Jetpack Security for **auth-token storage only**. The Room
database (`proofledger.db`) and the proof image files in `filesDir/proofs/`
are stored in cleartext, so on a rooted device — or a forensic dump from a
lost phone — every transaction and proof image is readable.

For the target ICP (§2.2 — financial data, distrustful users), this is a
real gap that v1 explicitly accepts but should not stay open forever.
Options when this is picked up:

* **SQLCipher for Room.** Drop-in encrypted SQLite. Adds a native lib
  (~2 MB APK size), needs a passphrase derived from device-keystore-backed
  material rather than a static string.
* **File-level encryption for the proof image directory** via
  `EncryptedFile` from `androidx.security:security-crypto`. Cheaper than
  SQLCipher, but each open/save has a per-file MAC cost — measure on
  low-end devices first.
* **Selective encryption** — only the `reference_number`, `note`, and
  counterparty fields, leaving structural data unencrypted. Smallest
  blast radius if performance matters.

Trigger to revisit: first real pilot with a business handling sensitive
payment data, OR a request from a security-conscious user, whichever
comes first.

---

## 21. Claude Code Build Instructions

When using Claude Code, do not ask it to build the whole system in one pass.

Use this pattern:

1. Give Claude the product principles and phase goal.
2. Ask it to inspect the current codebase.
3. Ask it to propose file changes before coding.
4. Approve one phase/chunk at a time.
5. Ask it to run tests/build after each phase.
6. Ask it to summarize what changed.
7. Commit each stable phase.

Recommended command style:

```text
We are building Phase 3: Manual Ledger MVP only. Do not implement OCR, screenshot upload, reports, payments, or WhatsApp yet. Inspect the codebase, identify the files that need to change, propose an implementation plan, then wait for approval before editing.
```

Keep Claude constrained.

Every phase should have:

* goal
* allowed scope
* forbidden scope
* files touched
* tests/build verification
* acceptance criteria

---

## 22. Acceptance Criteria for MVP

The MVP is acceptable when:

* User can create/select a business.
* User can manually add money in/out.
* User can attach screenshots to transactions.
* User can share a screenshot from WhatsApp/gallery into the app.
* App runs local OCR on the screenshot.
* App creates an editable transaction draft.
* User can confirm/edit and save transaction.
* Original screenshot is preserved.
* User can view/search transaction history.
* User can view dashboard totals.
* App works offline and syncs later.
* Duplicate screenshots are detected or warned.
* User can export basic summary or CSV.
* Errors do not cause data loss.

---

## 23. Non-Negotiables

The following are non-negotiable:

1. Android-native first.
2. Offline-first local save.
3. Screenshot preserved as proof.
4. AI/OCR creates drafts only.
5. User confirmation before final transaction.
6. Minimal required fields.
7. No full accounting in v1.
8. No bank integration in v1.
9. No WhatsApp bot in v1.
10. Clear sync/error states.
11. Clean relational data model.
12. Audit logs for financial edits.
13. Product language must be simple and non-accounting-heavy.

---

## 24. Final Product Direction

The product should feel like:

> A simple money memory for small businesses.

Not:

> A complicated finance system.

The app wins if a business owner or admin can say:

> I just send/upload my payment proofs, and now I always know who paid, who I paid, and what happened with the money.

That is the product experience every technical decision should support.
