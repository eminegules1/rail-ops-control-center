import Link from '@mui/material/Link'
import Typography from '@mui/material/Typography'
import { Link as RouterLink } from 'react-router'

export function NotFoundPage() {
  return (
    <>
      <Typography variant="h1" gutterBottom>
        Page not found
      </Typography>
      <Typography color="text.secondary">
        There is no page at this address.{' '}
        <Link component={RouterLink} to="/dashboard">
          Go to the dashboard
        </Link>
      </Typography>
    </>
  )
}
