import type { Config } from 'tailwindcss'

export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      colors: {
        bg: '#FAF9F6',
        surface: '#FFFFFF',
        border: '#E3E1DC',
        'text-primary': '#1C1C1A',
        'text-secondary': '#767671',
        accent: '#3B6E5E',
        'accent-subtle': '#E8EFEA',
      },
      fontFamily: {
        sans: ['Inter', '-apple-system', 'Segoe UI', 'sans-serif'],
      },
      borderRadius: {
        sm: '6px',
        DEFAULT: '10px',
      },
      boxShadow: {
        none: 'none',
      },
    },
  },
  plugins: [],
} satisfies Config
