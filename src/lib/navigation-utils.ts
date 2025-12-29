
let lastNavTime = 0;

/**
 * Prevents double-tap navigation by enforcing a minimum delay between actions.
 * @param callback The navigation action to execute
 * @param delay Minimum time in ms between actions (default 1000ms)
 */
export const preventDoubleTap = (callback: () => void, delay = 1000) => {
    const now = Date.now();
    if (now - lastNavTime < delay) return;
    lastNavTime = now;

    // Enforce 10ms delay for thread safety (User Rule 8.1)
    setTimeout(() => {
        callback();
    }, 10);
};
