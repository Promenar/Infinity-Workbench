# Lumina Design System

> [!IMPORTANT]
> This document serves as the **Source of Truth** for the Lumina UI. Use this to replicate the visual style in new projects.

## 1. Foundation
**Tech Stack**: TailwindCSS + Framer Motion (Web) / NativeWind + Reanimated (Mobile)
**Font**: `Roboto`, `sans-serif`

### Typography (Mobile Optimized)
| Element | Specs | Context |
|---------|-------|---------|
| **Large Title** | `text-3xl font-bold tracking-tighter` | Page Headers (Settings, Folders) |
| **Section Header** | `text-sm font-bold uppercase tracking-wider text-gray-400` | Grouped Lists |
| **Body** | `text-base` | Content |
| **Label** | `text-[10px] font-medium` | Tab Bar Labels |

### Color Palette
The design uses a semantic color system on top of a base Indigo scale.

#### Primitive Colors (Tailwind `colors.primary`)
| Shade | Hex |
|-------|-----|
| 50 | `#f0f3ff` |
| 100 | `#e0e7ff` |
| 200 | `#c7d2fe` |
| 300 | `#a5b4fc` |
| 400 | `#818cf8` |
| 500 | `#6366f1` (Brand Base) |
| 600 | `#4f46e5` |
| 700 | `#4338ca` |
| 800 | `#3730a3` |
| 900 | `#312e81` |

#### Semantic Tokens (CSS Variables)
Define these in your base CSS.

| Token | Light Mode Value | Dark Mode Value |
|-------|------------------|-----------------|
| `--surface-primary` | `#ffffff` | `#020617` (Slate 950) |
| `--surface-secondary` | `#f8fafc` (Slate 50) | `#0f172a` (Slate 900) |
| `--surface-tertiary` | `#f1f5f9` (Slate 100) | `#1e293b` (Slate 800) |
| `--text-primary` | `#0f172a` (Slate 900) | `#f1f5f9` (Slate 100) |
| `--text-secondary` | `#64748b` (Slate 500) | `#94a3b8` (Slate 400) |
| `--text-tertiary` | `#94a3b8` (Slate 400) | `#64748b` (Slate 500) |
| `--border-default` | `#e2e8f0` (Slate 200) | `#1e293b` (Slate 800) |
| `--border-subtle` | `#f1f5f9` (Slate 100) | `#0f172a` (Slate 900) |

### Global Utilities
#### Glassmorphism (`.glass`)
```css
.glass {
  background: rgba(255, 255, 255, 0.7);
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  transition: background-color 0.2s ease;
}
.dark .glass {
  background: rgba(2, 6, 23, 0.75);
  backdrop-filter: blur(16px);
  -webkit-backdrop-filter: blur(16px);
}
```

## 2. Components

### Button (`<Button />`)
**Base Interface**:
```tsx
interface ButtonProps {
  variant?: 'primary' | 'secondary' | 'ghost' | 'outline' | 'danger';
  size?: 'sm' | 'md' | 'lg';
  loading?: boolean;
}
```

**Variants Styling**:
- **Base**: `inline-flex items-center justify-center rounded-lg font-medium transition-colors focus:ring-2 focus:ring-primary-500`
- **Primary**: `bg-primary-600 hover:bg-primary-700 text-white shadow-sm`
- **Secondary**: `bg-surface-secondary hover:bg-surface-tertiary text-text-primary border border-border-default`
- **Ghost**: `bg-transparent hover:bg-surface-secondary text-text-secondary`
- **Outline**: `border border-border-default text-text-primary hover:bg-surface-secondary`
- **Danger**: `bg-red-50 text-red-600 hover:bg-red-100 dark:bg-red-900/20`

### Card (`<Card />`)
**Base Style**:
`bg-surface-primary border border-border-default rounded-xl overflow-hidden transition-all duration-300`

**Modifiers**:
- **Hover**: `hover:shadow-lg dark:hover:shadow-none hover:border-gray-300`
- **Interactive**: `cursor-pointer active:scale-[0.98]`
- **Active**: `ring-2 ring-primary-500 border-primary-500`

### Input (`<Input />`)
**Base Style**:
`bg-surface-secondary text-text-primary placeholder-text-tertiary border border-border-default rounded-lg px-3 py-2 text-sm focus:ring-2 focus:ring-primary-100 dark:focus:ring-primary-900/30 focus:border-primary-500 transition-all`

## 3. Layout Patterns

### Navigation
#### Bottom Tab Bar (Mobile Primary)
- **Container**: `bg-white dark:bg-black border-t border-gray-100 dark:border-white/10 flex-row justify-around pt-2 pb-safe`
- **Item**:
  - **Active**: Black (`#000`) icon + label (Light Mode) / White (`#fff`) icon + label (Dark Mode).
  - **Inactive**: Gray 400 (`#9ca3af`).
  - **Label**: `text-[10px] font-medium mt-1`.
  - **NO/Minimal Background**: No pill/capsule background behind icons.

#### Navigation Sidebar (Desktop Only)
- **Collapsed**: `w-64 -translate-x-full`
- **Expanded**: `translate-x-0`
- **Visuals**:
  - **Light Mode**: `bg-surface-primary border-r border-border-default text-text-primary`
  - **"Home" Mode (Immersive)**: `bg-gray-900 border-r border-gray-800 text-gray-100`

### Inset Grouped List (Settings/Menus)
**Container**:
- **Wrapper**: `px-6 pt-6` (Screen Padding)
- **Group**: `bg-gray-50 dark:bg-zinc-900 p-4 rounded-xl border border-gray-100 dark:border-zinc-800 mb-4`

**Items**:
- **Row**: `flex-row items-center justify-between p-3 rounded-lg active:bg-gray-200 dark:active:bg-zinc-800`
- **Divider**: Implicit or `border-b` between items if not using card style.

### Modal (Floating / Suspended)
**Animation (Framer Motion)**:
- **Entrance**: Scale `0.95` -> `1`, Y `20px` -> `0px`, Opacity `0` -> `1` (Spring: stiff 300, damping 30)
- **Container**: `fixed inset-0 z-50 bg-black/70 backdrop-blur-sm`
- **Content**: `bg-white dark:bg-gray-900 w-full max-w-5xl rounded-3xl shadow-2xl border border-gray-200 dark:border-gray-800`

### Progress Bar
**Structure**:
```tsx
<div className="h-2 bg-gray-100 dark:bg-gray-800 rounded-full overflow-hidden">
  <motion.div
    className="h-full bg-primary-500"
    initial={{ width: 0 }}
    animate={{ width: `${percentage}%` }}
    transition={{ duration: 0.5 }}
  />
</div>
```
- **Container**: `h-2 bg-gray-100 dark:bg-gray-800 rounded-full`
- **Fill**: `h-full bg-primary-500` (Animated width)

## 4. Animation Guidelines
- **Micro-interactions**: Use `active:scale-95` or `active:scale-[0.98]` for clickable cards/buttons.
- **Page Transitions**: `animate-in fade-in slide-in-from-right-4 duration-300` (using `tailwindcss-animate` or similar).
- **Lists**: Staggered items.

## 5. Visual Reference Gallery
````carousel
![Settings Screen - Inset Grouped List](/C:/Users/lengz/.gemini/antigravity/brain/26eae043-e87c-4fa5-a1de-e355213056d6/uploaded_image_0_1766595131085.jpg)
<!-- slide -->
![Favorites Screen - Card Grid](/C:/Users/lengz/.gemini/antigravity/brain/26eae043-e87c-4fa5-a1de-e355213056d6/uploaded_image_1_1766595131085.jpg)
<!-- slide -->
![Folders Screen - Large Title](/C:/Users/lengz/.gemini/antigravity/brain/26eae043-e87c-4fa5-a1de-e355213056d6/uploaded_image_2_1766595131085.jpg)
<!-- slide -->
![Home Screen - Hero Carousel & Tabs](/C:/Users/lengz/.gemini/antigravity/brain/26eae043-e87c-4fa5-a1de-e355213056d6/uploaded_image_3_1766595131085.jpg)
````
