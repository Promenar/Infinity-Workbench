import { Platform } from 'react-native';

/**
 * Unified Glassmorphism Constants
 * 
 * Defines the standard blur intensity and opacity values for the application.
 * 
 * - **Header**: Used for top navigation bars and bottom input areas. High blur, low opacity.
 * - **Overlay**: Used for floating cards, modals, and toasts. Medium blur, medium opacity.
 * - **Sheet**: Used for bottom sheets. Lower blur, high opacity for content legibility and privacy.
 */
export const Glass = {
    /**
     * Header & Input Area
     * Target: "Heavy Glass" - High Blur, Low Opacity
     */
    Header: {
        intensity: Platform.OS === 'android' ? 50 : 70, // Android limited to 50 generally for performance
        opacity: {
            light: 0.25,
            dark: 0.15, // Slightly lower for dark mode to blend better
        },
        tint: {
            light: 'default',
            dark: 'dark',
        } as const,
    },

    /**
     * Floating Cards / Modals / Toasts
     * Target: "Medium Glass" - Medium Blur, Medium Opacity
     */
    Overlay: {
        intensity: Platform.OS === 'android' ? 30 : 50,
        opacity: {
            light: 0.80, // Revert slightly from 0.88, rely on white base
            dark: 0.75,  // Revert slightly from 0.80, rely on black base
        },
        baseColor: {
            light: '255, 255, 255',
            dark: '0, 0, 0',
        },
        tint: {
            light: 'light',
            dark: 'dark',
        } as const,
    },

    /**
     * Bottom Sheets (Privacy Layer)
     * Target: "Frosted Glass" - Low Blur, High Opacity
     */
    Sheet: {
        intensity: Platform.OS === 'android' ? 25 : 40,
        opacity: {
            light: 0.96,
            dark: 0.96,
        },
        baseColor: {
            light: '255, 255, 255',
            dark: '0, 0, 0', // Explicitly black
        },
        tint: {
            light: 'default',
            dark: 'dark',
        } as const,
    },
};

/**
 * Unified Shadow Constants
 * 
 * Standard shadow styles for consistent elevation across the app.
 */
export const Shadows = {
    /**
     * Small shadow - Used for subtle elevation (switches, small cards)
     */
    sm: {
        shadowColor: '#000',
        shadowOffset: { width: 0, height: 2 },
        shadowOpacity: 0.1,
        shadowRadius: 2,
        elevation: 2,
    },
    /**
     * Medium shadow - Used for floating elements (context menus)
     */
    md: {
        shadowColor: '#000',
        shadowOffset: { width: 0, height: 8 },
        shadowOpacity: 0.12,
        shadowRadius: 24,
        elevation: 16,
    },
    /**
     * Large shadow - Used for modals, bottom sheets, alerts
     */
    lg: {
        shadowColor: '#000',
        shadowOffset: { width: 0, height: 10 },
        shadowOpacity: 0.2,
        shadowRadius: 20,
        elevation: 10,
    },
    /**
     * Glow shadow - Used for editor modals with brand color tint
     */
    glow: {
        shadowOffset: { width: 0, height: 4 },
        shadowOpacity: 0.1,
        shadowRadius: 15,
        elevation: 20,
    },
};
