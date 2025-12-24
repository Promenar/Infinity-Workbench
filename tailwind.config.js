/** @type {import('tailwindcss').Config} */
module.exports = {
  // NOTE: Update this to include the paths to all of your component files.
  content: ["./app/**/*.{js,jsx,ts,tsx}", "./src/**/*.{js,jsx,ts,tsx}"],
  presets: [require("nativewind/preset")],
  theme: {
    extend: {
      colors: {
        background: 'var(--surface-secondary)',
        'surface-primary': 'var(--surface-primary)',
        'surface-secondary': 'var(--surface-secondary)',
        'surface-tertiary': 'var(--surface-tertiary)',
        'text-primary': 'var(--text-primary)',
        'text-secondary': 'var(--text-secondary)',
        'text-tertiary': 'var(--text-tertiary)',
        'border-default': 'var(--border-default)',
        primary: {
           50: '#f0f3ff',
           100: '#e0e7ff',
           200: '#c7d2fe',
           300: '#a5b4fc',
           400: '#818cf8',
           500: '#6366f1',
           600: '#4f46e5',
           700: '#4338ca',
           800: '#3730a3',
           900: '#312e81',
        }
      }
    },
  },
  plugins: [],
}
