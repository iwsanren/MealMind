import { useEffect, useState } from 'react'
import { getUserId, USER_ID_CHANGE_EVENT } from './apiClient'

export function useUserId(): string {
  const [userId, setUserIdState] = useState(getUserId)

  useEffect(() => {
    const handleChange = () => setUserIdState(getUserId())
    window.addEventListener(USER_ID_CHANGE_EVENT, handleChange)
    return () => window.removeEventListener(USER_ID_CHANGE_EVENT, handleChange)
  }, [])

  return userId
}
