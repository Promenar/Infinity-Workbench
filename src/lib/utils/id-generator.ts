/**
 * Generate a unique ID without relying on crypto.getRandomValues()
 * Format: timestamp-randomString
 * 
 * This utility replaces uuid to avoid crypto polyfill issues in React Native
 */
export function generateId(): string {
    const timestamp = Date.now().toString(36);
    const randomPart = Math.random().toString(36).substring(2, 15);
    const randomPart2 = Math.random().toString(36).substring(2, 15);
    return `${timestamp}-${randomPart}${randomPart2}`;
}
