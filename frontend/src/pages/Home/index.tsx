import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { getPersonalMeals, getPublicMeals } from '../../api/meals'
import { buttonClassName } from '../../components/Button'
import { useUserId } from '../../lib/useUserId'

interface StatCardProps {
  label: string
  value: string
}

function StatCard({ label, value }: StatCardProps) {
  return (
    <div className="flex flex-col gap-1 rounded border border-border bg-surface p-4">
      <span className="text-[13px] text-text-secondary">{label}</span>
      <span className="text-[24px] font-semibold">{value}</span>
    </div>
  )
}

interface FeatureCardProps {
  title: string
  description: string
  to: string
  cta: string
}

function FeatureCard({ title, description, to, cta }: FeatureCardProps) {
  return (
    <div className="flex flex-col gap-3 rounded border border-border bg-surface p-5">
      <h3 className="text-[16px] font-semibold">{title}</h3>
      <p className="flex-1 text-[14px] text-text-secondary">{description}</p>
      <Link to={to} className={buttonClassName('ghost', 'self-start')}>
        {cta}
      </Link>
    </div>
  )
}

export function HomePage() {
  const userId = useUserId()
  const [personalCount, setPersonalCount] = useState<number | null>(null)
  const [publicCount, setPublicCount] = useState<number | null>(null)

  useEffect(() => {
    let cancelled = false

    Promise.allSettled([getPersonalMeals(), getPublicMeals()]).then(([personal, publicMeals]) => {
      if (cancelled) return
      setPersonalCount(personal.status === 'fulfilled' ? personal.value.length : null)
      setPublicCount(publicMeals.status === 'fulfilled' ? publicMeals.value.length : null)
    })

    return () => {
      cancelled = true
    }
  }, [userId])

  return (
    <div className="mx-auto flex max-w-5xl flex-col gap-8 px-6 py-10">
      <div className="flex flex-col gap-4 rounded border border-border bg-surface p-8">
        <span className="inline-block w-fit rounded-sm bg-accent-subtle px-2 py-1 text-[12px] font-medium text-accent">
          AI-assisted recommendations
        </span>
        <h1 className="text-[28px] font-semibold">
          Decide what to eat, without the back-and-forth.
        </h1>
        <p className="max-w-2xl text-text-secondary">
          Keep a library of meals you actually eat, or start from the public catalog. MealMind
          asks a quick question when it needs to, then narrows by time of day, mood, setting, and
          effort.
        </p>
        <div className="flex flex-wrap gap-3">
          <Link to="/chat" className={buttonClassName('primary')}>
            Start chatting
          </Link>
          <Link to="/meals/personal" className={buttonClassName('secondary')}>
            Manage my meals
          </Link>
          <Link to="/meals/public" className={buttonClassName('ghost')}>
            Browse library
          </Link>
        </div>
      </div>

      <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
        <StatCard label="Personal meals" value={personalCount === null ? '—' : String(personalCount)} />
        <StatCard label="Public meals" value={publicCount === null ? '—' : String(publicCount)} />
        <StatCard label="Signed in as" value={userId} />
      </div>

      <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
        <FeatureCard
          title="Chat recommendations"
          description="Tell MealMind how you feel and where you are; it asks a quick follow-up and suggests a meal."
          to="/chat"
          cta="Start chatting"
        />
        <FeatureCard
          title="Meal library"
          description="Browse the public catalog or manage the personal meals you actually eat."
          to="/meals/public"
          cta="Browse library"
        />
        <FeatureCard
          title="Trace & evaluation"
          description="Inspect the reasoning behind a recommendation and review evaluation reports."
          to="/admin/traces"
          cta="View traces"
        />
      </div>
    </div>
  )
}

export default HomePage
