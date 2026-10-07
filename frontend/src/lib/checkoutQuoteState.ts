import type { BillingPeriod, OrderDeductionMode } from "../types";

/** The exact identity of a quote and the order request it authorizes. */
export type CheckoutQuoteInput = {
  ownerId: string;
  planId: string;
  period: BillingPeriod;
  couponCode: string | null;
  deductionMode: OrderDeductionMode;
};

export type CheckoutQuoteSnapshot<Quote> = {
  input: CheckoutQuoteInput | null;
  quote: Quote | null;
  error: string | null;
  pending: boolean;
};

export type CheckoutOrderInput = Pick<
  CheckoutQuoteInput,
  "planId" | "period" | "couponCode" | "deductionMode"
>;

/** Uses the exact accepted quote identity to build the mutation variables. */
export function orderInputForQuote(input: CheckoutQuoteInput): CheckoutOrderInput {
  return {
    planId: input.planId,
    period: input.period,
    couponCode: input.couponCode,
    deductionMode: input.deductionMode
  };
}

function copyInput(input: CheckoutQuoteInput): CheckoutQuoteInput {
  return Object.freeze({ ...input });
}

export function checkoutQuoteInputKey(input: CheckoutQuoteInput): string {
  return JSON.stringify([
    input.ownerId,
    input.planId,
    input.period,
    input.couponCode,
    input.deductionMode
  ]);
}

/**
 * Keeps only the latest quote identity eligible to update checkout state.
 * A generation changes when any input changes; a request sequence changes for
 * every fetch, including duplicate fetches caused by a StrictMode remount.
 */
export class CheckoutQuoteState<Quote> {
  private listeners = new Set<() => void>();
  private selectedKey: string | null = null;
  private generation = 0;
  private requestSequence = 0;
  private snapshot: CheckoutQuoteSnapshot<Quote> = {
    input: null,
    quote: null,
    error: null,
    pending: false
  };

  subscribe = (listener: () => void) => {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  };

  getSnapshot = () => this.snapshot;

  /** Invalidates an old quote immediately, before the next fetch starts. */
  select(input: CheckoutQuoteInput): void {
    const key = checkoutQuoteInputKey(input);
    if (key === this.selectedKey) return;

    this.selectedKey = key;
    this.generation++;
    this.requestSequence++;
    this.snapshot = {
      input: copyInput(input),
      quote: null,
      error: null,
      pending: true
    };
    this.publish();
  }

  /** Reprices an unchanged selection after an order was cancelled. */
  invalidate(input: CheckoutQuoteInput): void {
    const key = checkoutQuoteInputKey(input);
    this.selectedKey = key;
    this.generation++;
    this.requestSequence++;
    this.snapshot = {
      input: copyInput(input),
      quote: null,
      error: null,
      pending: true
    };
    this.publish();
  }

  /** Invalidates work when checkout has no selected period or owner. */
  clear(): void {
    this.selectedKey = null;
    this.generation++;
    this.requestSequence++;
    this.snapshot = {
      input: null,
      quote: null,
      error: null,
      pending: false
    };
    this.publish();
  }

  async request(
    input: CheckoutQuoteInput,
    fetchQuote: (selectedInput: CheckoutQuoteInput) => Promise<Quote>,
    formatError: (error: unknown) => string
  ): Promise<void> {
    const key = checkoutQuoteInputKey(input);
    if (key !== this.selectedKey) this.select(input);

    const generation = this.generation;
    const sequence = ++this.requestSequence;
    const selectedInput = copyInput(input);
    this.snapshot = {
      input: selectedInput,
      quote: null,
      error: null,
      pending: true
    };
    this.publish();

    try {
      const quote = await fetchQuote(selectedInput);
      if (!this.isCurrent(key, generation, sequence)) return;
      this.snapshot = {
        input: selectedInput,
        quote,
        error: null,
        pending: false
      };
      this.publish();
    } catch (error) {
      if (!this.isCurrent(key, generation, sequence)) return;
      this.snapshot = {
        input: selectedInput,
        quote: null,
        error: formatError(error),
        pending: false
      };
      this.publish();
    }
  }

  confirmed(input: CheckoutQuoteInput): {
    input: CheckoutQuoteInput;
    quote: Quote;
  } | null {
    const key = checkoutQuoteInputKey(input);
    if (
      key !== this.selectedKey ||
      this.snapshot.pending ||
      this.snapshot.error !== null ||
      this.snapshot.quote === null ||
      this.snapshot.input === null ||
      checkoutQuoteInputKey(this.snapshot.input) !== key
    ) {
      return null;
    }
    return { input: copyInput(this.snapshot.input), quote: this.snapshot.quote };
  }

  private isCurrent(key: string, generation: number, sequence: number) {
    return key === this.selectedKey &&
      generation === this.generation &&
      sequence === this.requestSequence;
  }

  private publish() {
    for (const listener of this.listeners) listener();
  }
}

export function canonicalCoupon(value: string): string | null {
  return value.trim() || null;
}
