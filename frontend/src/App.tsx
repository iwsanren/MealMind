import { BrowserRouter, Route, Routes } from 'react-router-dom'
import { AppShell } from './components/layout/AppShell'
import { ChatPage } from './pages/Chat'
import { EvaluationsPage } from './pages/Evaluations'
import { HomePage } from './pages/Home'
import { PersonalMealsPage } from './pages/PersonalMeals'
import { PublicMealsPage } from './pages/PublicMeals'
import { TracePage } from './pages/Trace'

function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route element={<AppShell />}>
          <Route path="/" element={<HomePage />} />
          <Route path="/chat" element={<ChatPage />} />
          <Route path="/meals/personal" element={<PersonalMealsPage />} />
          <Route path="/meals/public" element={<PublicMealsPage />} />
          <Route path="/admin/traces" element={<TracePage />} />
          <Route path="/admin/evaluations" element={<EvaluationsPage />} />
        </Route>
      </Routes>
    </BrowserRouter>
  )
}

export default App
