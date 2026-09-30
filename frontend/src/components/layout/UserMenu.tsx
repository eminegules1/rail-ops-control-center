import Avatar from '@mui/material/Avatar'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import { endSession, useSession } from '../../lib/session'

/** Who is signed in (avatar, name with the role under it), and the way out. */
export function UserMenu() {
  const session = useSession()
  if (!session) return null

  return (
    <Stack direction="row" spacing={1.5} sx={{ alignItems: 'center' }}>
      <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
        <Avatar aria-hidden sx={{ width: 26, height: 26, fontSize: '0.8125rem', bgcolor: 'primary.main' }}>
          {session.username.charAt(0).toUpperCase()}
        </Avatar>
        <Box sx={{ display: 'flex', flexDirection: 'column', lineHeight: 1 }}>
          <Typography variant="body2" sx={{ fontWeight: 600, fontSize: '0.8125rem' }}>
            {session.username}
          </Typography>
          <Typography
            variant="caption"
            sx={{ fontSize: '0.6875rem', color: 'text.secondary', textTransform: 'uppercase', letterSpacing: '0.04em' }}
          >
            {session.role}
          </Typography>
        </Box>
      </Stack>
      <Button
        size="small"
        variant="text"
        color="inherit"
        sx={{ color: 'text.secondary', '&:hover': { color: 'text.primary' }, textTransform: 'none', fontWeight: 500 }}
        onClick={() => endSession('signed-out')}
      >
        Sign out
      </Button>
    </Stack>
  )
}
