import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import { useServices } from '../api/services'
import { BackendErrorToast } from '../components/layout/BackendErrorToast'
import { ServiceStatusTable } from '../components/services/ServiceStatusTable'

export function ServicesPage() {
  const { data, isPending, isError } = useServices()

  return (
    // minmax(0, …) lets the wide table scroll inside its panel instead of widening the page.
    <Box sx={{ display: 'grid', gap: 2, gridTemplateColumns: 'minmax(0, 1fr)' }}>
      <Typography variant="h1">Services</Typography>
      <ServiceStatusTable services={data} loading={isPending} failed={isError && !data} />
      <BackendErrorToast open={isError} />
    </Box>
  )
}
