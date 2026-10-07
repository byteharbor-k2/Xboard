import {
  canonicalCoupon,
  CheckoutQuoteState,
  checkoutQuoteInputKey,
  orderInputForQuote,
  type CheckoutQuoteInput
} from "../src/lib/checkoutQuoteState.ts";

let failures = 0;

function check(name: string, passed: boolean, detail = "") {
  console.log(`${passed ? "PASS" : "FAIL"}  ${name}${passed ? "" : `   [${detail}]`}`);
  if (!passed) failures++;
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason: unknown) => void;
  const promise = new Promise<T>((yes, no) => {
    resolve = yes;
    reject = no;
  });
  return { promise, resolve, reject };
}

const initial: CheckoutQuoteInput = {
  ownerId: "owner-A",
  planId: "plan-A",
  period: "MONTHLY",
  couponCode: "SAVE10",
  deductionMode: "STANDARD"
};

async function main() {
  console.log("--- case 1: reverse quote completion cannot replace the latest quote ---");
  {
    const state = new CheckoutQuoteState<string>();
    const older = deferred<string>();
    const newer = deferred<string>();
    const inputB = { ...initial, deductionMode: "FULL_PAYMENT" as const };
    state.select(initial);
    const requestA = state.request(initial, () => older.promise, () => "old error");
    state.select(inputB);
    const requestB = state.request(inputB, () => newer.promise, () => "new error");

    newer.resolve("quote-B");
    await requestB;
    older.resolve("quote-A");
    await requestA;

    const confirmed = state.confirmed(inputB);
    check(
      "late A success leaves B as the displayed and submit-eligible quote",
      confirmed?.quote === "quote-B" &&
        checkoutQuoteInputKey(confirmed.input) === checkoutQuoteInputKey(inputB),
      JSON.stringify(state.getSnapshot())
    );
  }

  console.log("--- case 2: late old failure cannot overwrite a successful latest quote ---");
  {
    const state = new CheckoutQuoteState<string>();
    const older = deferred<string>();
    const newer = deferred<string>();
    const inputB = { ...initial, period: "QUARTERLY" as const };
    state.select(initial);
    const requestA = state.request(initial, () => older.promise, () => "stale failure");
    state.select(inputB);
    const requestB = state.request(inputB, () => newer.promise, () => "current failure");

    newer.resolve("quote-B");
    await requestB;
    older.reject(new Error("old request failed late"));
    await requestA;

    const confirmed = state.confirmed(inputB);
    check(
      "late A error neither replaces B nor sets an error",
      confirmed?.quote === "quote-B" && state.getSnapshot().error === null,
      JSON.stringify(state.getSnapshot())
    );
  }

  console.log("--- case 3: owner and every commercial input change invalidate old work ---");
  {
    const changedInputs: Array<[string, CheckoutQuoteInput]> = [
      ["owner", { ...initial, ownerId: "owner-B" }],
      ["plan", { ...initial, planId: "plan-B" }],
      ["period", { ...initial, period: "YEARLY" }],
      ["coupon", { ...initial, couponCode: "OTHER" }],
      ["deduction mode", { ...initial, deductionMode: "FULL_PAYMENT" }]
    ];
    for (const [field, next] of changedInputs) {
      const state = new CheckoutQuoteState<string>();
      const old = deferred<string>();
      state.select(initial);
      const oldRequest = state.request(initial, () => old.promise, () => "stale");
      state.select(next);
      old.resolve("old quote");
      await oldRequest;
      check(
        `${field} change prevents old quote commit and submission`,
        state.confirmed(next) === null &&
          state.getSnapshot().quote === null &&
          state.getSnapshot().error === null,
        JSON.stringify(state.getSnapshot())
      );
    }
  }

  console.log("--- case 4: mutation variables come from the displayed confirmed quote ---");
  {
    const state = new CheckoutQuoteState<{ renderedFor: string }>();
    const selected: CheckoutQuoteInput = {
      ...initial,
      ownerId: "owner-C",
      planId: "plan-C",
      period: "HALF_YEARLY",
      couponCode: canonicalCoupon("  SPRING  "),
      deductionMode: "FULL_PAYMENT"
    };
    let sentToQuote: CheckoutQuoteInput | null = null;
    state.select(selected);
    await state.request(
      selected,
      async (input) => {
        sentToQuote = input;
        return { renderedFor: checkoutQuoteInputKey(input) };
      },
      (error) => String(error)
    );

    const shown = state.confirmed(selected);
    const mutation = shown ? orderInputForQuote(shown.input) : null;
    check(
      "coupon is canonical before quoting",
      sentToQuote?.couponCode === "SPRING"
    );
    check(
      "displayed quote identity equals the exact order mutation inputs",
      shown !== null && mutation !== null &&
        shown.quote.renderedFor === checkoutQuoteInputKey(selected) &&
        mutation.planId === sentToQuote?.planId &&
        mutation.period === sentToQuote?.period &&
        mutation.couponCode === sentToQuote?.couponCode &&
        mutation.deductionMode === sentToQuote?.deductionMode &&
        shown.input.ownerId === sentToQuote?.ownerId,
      JSON.stringify({ shown, mutation, sentToQuote })
    );
    state.invalidate(selected);
    check(
      "explicit repricing immediately disables use of the previous quote",
      state.confirmed(selected) === null && state.getSnapshot().pending
    );
  }

  console.log(failures === 0 ? "\nALL CHECKS PASSED" : `\n${failures} CHECK(S) FAILED`);
  if (failures > 0) process.exitCode = 1;
}

void main();
