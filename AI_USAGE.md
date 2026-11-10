# AI Assistance and Validation

Gametime welcomes AI use and asks candidates to disclose **where and why** they used it and **how they checked or challenged it**. I used AI for the initial draft and follow-up review, then ran the tests and API scenarios in my local workspace.

## Where AI helped

- **Initial draft:** I used ChatGPT to organize the assessment requirements and draft the Java state machine, payment/fulfillment interfaces, demo adapters, HTTP layer, tests, README, and initial AI disclosure.
- **Follow-up review:** I used GitHub Copilot in VS Code to compare the implementation with the assessment, add HTTP and terminal-state tests, register demo scenarios before publishing orders, rename the exception source file, run the tests and live API scenarios, and update this disclosure.

## Why AI was useful

The assessment has a short time expectation. AI helped me draft routine structure quickly so I could focus on the stage-dependent recovery behavior. I treated its output as a draft and checked the failure branches with automated tests, live HTTP requests, and code review.

## What I Ran and Checked

- I ran `./test.sh` with **Temurin OpenJDK 21.0.13** on November 10, 2026. All six test groups passed.
- I started the service with `PORT=8082 ./run.sh`, sent requests with `curl` for all four scenarios, and checked `GET /orders/{id}` state and history:
	- `HAPPY_PATH`: authorize `200`, complete `200`, final state `complete`, 3 history entries.
	- `PAYMENT_DECLINED`: authorize `422`, final state `rejected`, 2 history entries; the unit test verifies no void is called.
	- `COMPLETION_FAILURE_VOID_SUCCESS`: authorize `200`, complete `422`, final state `cancelled`, 3 history entries.
	- `COMPLETION_FAILURE_VOID_FAILED`: authorize `200`, complete `502`, final state `needs_attention`, 3 history entries.
- I reviewed `OrderService.complete`: the order becomes `cancelled` only after the payment void succeeds. If the void fails, the order becomes `needs_attention` and the error is surfaced instead of claiming cancellation.
- I added HTTP integration assertions for payment decline (`422`, `rejected`), completion failure with a successful void (`422`, `cancelled`), failed void (`502`, `needs_attention`), and invalid actions on terminal orders (`409`, unchanged history).
- I added service-level checks that both `authorize` and `complete` leave terminal states unchanged and make no downstream calls.
- I registered each demo scenario before adding its order to the service, so an order cannot be observed with the default scenario before its requested scenario is applied.

## What I Challenged or Excluded

- A follow-up review found that HTTP coverage missed payment decline and successful-void recovery, and terminal-state checks were incomplete; I added those HTTP cases and verified terminal actions return `409` without changing history or calling dependencies.
- I did not mark an order `cancelled` unless the payment void succeeded; a failed void is surfaced as `needs_attention`.
- I did not add automatic void retries because the assessment asks for manual attention after a failed void and does not specify retry or idempotency behavior.
- I kept the prototype dependency-free and in memory rather than adding a framework, database, queue, or distributed workflow that the assessment does not require.
- I kept demo scenario selection in the stubs and HTTP adapter, outside the domain state rules.

## Submission Checklist

- [x] I ran `./test.sh`; all six test groups passed on this machine.
- [x] I ran the service, exercised all four scenarios with `curl`, and inspected each order's GET state and history.
- [x] I reviewed why `cancelled` depends on a successful void; otherwise the order is `needs_attention`.
- [x] I chose Java 21 and the dependency-free HTTP server to keep this small prototype within the assessment's scope.
- [x] I updated the HTTP integration tests and this disclosure to reflect the implementation and checks performed.
- [x] I confirmed the GitHub repository is public and reviewed the project files for common accidental-secret patterns.
