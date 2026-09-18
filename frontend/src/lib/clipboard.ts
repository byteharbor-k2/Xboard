/**
 * Put text on the clipboard.
 *
 * `navigator.clipboard` is unavailable outside a secure context and can be
 * refused even on a real click, so the hidden-textarea fallback stays: without
 * it the copy button silently does nothing on a plain-HTTP deployment, which is
 * exactly where someone is most likely to be testing.
 */
export async function copyText(value: string): Promise<void> {
  try {
    await navigator.clipboard.writeText(value);
    return;
  } catch {
    // Fall through to the selection-based path below.
  }

  const input = document.createElement("textarea");
  input.value = value;
  input.style.position = "fixed";
  input.style.opacity = "0";
  document.body.appendChild(input);
  input.select();
  document.execCommand("copy");
  input.remove();
}
