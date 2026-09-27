import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import { BackendErrorToast } from '../components/dashboard/BackendErrorToast'
import { KpiCards } from '../components/dashboard/KpiCards'
import { RecentEvents } from '../components/dashboard/RecentEvents'
import { ServiceHealthGrid } from '../components/dashboard/ServiceHealthGrid'
import { SeverityChart } from '../components/dashboard/SeverityChart'
import { TimelineChart } from '../components/dashboard/TimelineChart'

export function DashboardPage() {
  return (
    // minmax(0, …) lets wide children (the events table) scroll inside their panel instead of widening the page.
    <Box sx={{ display: 'grid', gap: 2, gridTemplateColumns: 'minmax(0, 1fr)' }}>
      <Typography variant="h1">Dashboard</Typography>
      <KpiCards />
      <ServiceHealthGrid />
      <Box
        sx={{ display: 'grid', gap: 2, gridTemplateColumns: { xs: 'minmax(0, 1fr)', lg: 'minmax(0, 1fr) minmax(0, 2fr)' } }}
      >
        <SeverityChart />
        <TimelineChart />
      </Box>
      <RecentEvents />
      <BackendErrorToast />
    </Box>
  )
}
