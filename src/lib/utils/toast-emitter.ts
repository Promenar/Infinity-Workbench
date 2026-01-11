import { EventEmitter } from 'eventemitter3';

type ToastType = 'success' | 'error' | 'info' | 'warning';

interface ToastEvent {
    message: string;
    type: ToastType;
}

export const toastEmitter = new EventEmitter();

export const emitToast = (message: string, type: ToastType = 'info') => {
    toastEmitter.emit('toast', { message, type });
};
