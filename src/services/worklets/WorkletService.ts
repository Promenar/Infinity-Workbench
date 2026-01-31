import { Worklets } from 'react-native-worklets-core';
import { runOnUI as _runOnUI } from 'react-native-reanimated';

/**
 * WorkletService for managing background threads and shared values.
 * This service abstracts the underlying worklet implementation (react-native-worklets-core).
 */
/**
 * Run a function on a background thread.
 * @param worklet The function to run. It must be a worklet.
 */
export function runOnBackground(worklet: () => void) {
    return Worklets.defaultContext.runAsync(worklet);
}

/**
 * Run a function on the JS thread.
 * Only use this if you are already on a nice worklet thread.
 * @param func The function to run.
 */
export function runOnJS(func: () => void) {
    'worklet';
    Worklets.runOnJS(func);
}

/**
 * Run a function on the UI thread (via Reanimated).
 * @param worklet The function to run.
 */
export function runOnUI(worklet: () => void) {
    'worklet';
    _runOnUI(worklet)();
}

/**
 * Create a shared value.
 */
export function createSharedValue<T>(initialValue: T) {
    return Worklets.createSharedValue(initialValue);
}

/**
 * Create a function that executes on the JS thread.
 * This is useful for creating callbacks that update UI state.
 */
export function createRunOnJS<TArgs extends unknown[], TReturn>(
    func: (...args: TArgs) => TReturn
) {
    return Worklets.createRunOnJS(func);
}

/**
 * WorkletService namespace for backward compatibility and organized access.
 */
export const WorkletService = {
    runOnBackground,
    runOnJS,
    createRunOnJS,
    runOnUI,
    createSharedValue,
    createDedicatedContext: Worklets.createContext,
};
