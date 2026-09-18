import { NavLink, Outlet } from 'react-router-dom'
import { UserIdField } from './UserIdField'

const NAV_ITEMS = [
  { label: 'Home', to: '/' },
  { label: 'Chat', to: '/chat' },
  { label: 'Personal Meals', to: '/meals/personal' },
  { label: 'Public Meals', to: '/meals/public' },
  { label: 'Trace', to: '/admin/traces' },
  { label: 'Evaluations', to: '/admin/evaluations' },
]

export function AppShell() {
  return (
    <div className="min-h-screen bg-bg">
      <header className="flex items-center justify-between gap-6 border-b border-border bg-surface px-6 py-3">
        <div className="flex items-center gap-3">
          <span className="flex h-9 w-9 items-center justify-center rounded bg-accent text-[16px] font-semibold text-white">
            M
          </span>
          <div className="flex flex-col">
            <span className="text-[15px] font-semibold leading-tight">MealMind</span>
            <span className="text-[12px] leading-tight text-text-secondary">
              Fewer decisions, better meals
            </span>
          </div>
        </div>

        <nav className="flex flex-wrap items-center gap-1">
          {NAV_ITEMS.map((item) => (
            <NavLink
              key={item.to}
              to={item.to}
              end={item.to === '/'}
              className={({ isActive }) =>
                [
                  'rounded-sm px-3 py-1.5 text-[13px] font-medium transition-colors',
                  isActive ? 'bg-accent-subtle text-accent' : 'text-text-secondary hover:text-text-primary',
                ].join(' ')
              }
            >
              {item.label}
            </NavLink>
          ))}
        </nav>

        <UserIdField />
      </header>

      <main>
        <Outlet />
      </main>
    </div>
  )
}
