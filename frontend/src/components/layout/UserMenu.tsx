import { useState, type ReactElement } from 'react'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Popover from '@mui/material/Popover'
import Stack from '@mui/material/Stack'
import SvgIcon from '@mui/material/SvgIcon'
import Typography from '@mui/material/Typography'
import { endSession, useSession } from '../../lib/session'

type Role = 'ADMIN' | 'VIEWER'

// Material Design icon paths, drawn inline so no icon package is needed.
const ShieldIcon = () => (
  <SvgIcon fontSize="small">
    <path d="M12 1 3 5v6c0 5.55 3.84 10.74 9 12 5.16-1.26 9-6.45 9-12V5l-9-4z" />
  </SvgIcon>
)
const EyeIcon = () => (
  <SvgIcon fontSize="small">
    <path d="M12 4.5C7 4.5 2.73 7.61 1 12c1.73 4.39 6 7.5 11 7.5s9.27-3.11 11-7.5c-1.73-4.39-6-7.5-11-7.5zM12 17c-2.76 0-5-2.24-5-5s2.24-5 5-5 5 2.24 5 5-2.24 5-5 5zm0-8c-1.66 0-3 1.34-3 3s1.34 3 3 3 3-1.34 3-3-1.34-3-3-3z" />
  </SvgIcon>
)
const CheckIcon = () => (
  <SvgIcon fontSize="small">
    <path d="M9 16.17 4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z" />
  </SvgIcon>
)
const ChevronDownIcon = () => (
  <SvgIcon fontSize="small">
    <path d="M7.41 8.59 12 13.17l4.59-4.58L18 10l-6 6-6-6z" />
  </SvgIcon>
)

const ROLES: Record<Role, { label: string; description: string; color: string; icon: ReactElement }> = {
  ADMIN: {
    label: 'Admin',
    description: 'Full access: Modify incident status, acknowledge, resolve, reopen.',
    color: '#2e7d32',
    icon: <ShieldIcon />,
  },
  VIEWER: {
    label: 'Viewer',
    description: 'Read-only: Telemetry monitoring without modification permissions.',
    color: '#0288d1',
    icon: <EyeIcon />,
  },
}

const MENU_ID = 'access-control-menu'

/** The signed-in role badge with its access-control popover, and the way out. */
export function UserMenu() {
  const session = useSession()
  const [anchor, setAnchor] = useState<HTMLElement | null>(null)
  if (!session) return null

  const current = ROLES[session.role]
  const open = anchor !== null

  return (
    <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
      <Button
        size="small"
        variant="outlined"
        aria-haspopup="dialog"
        aria-expanded={open}
        aria-controls={open ? MENU_ID : undefined}
        startIcon={current.icon}
        endIcon={<ChevronDownIcon />}
        onClick={(event) => setAnchor(event.currentTarget)}
        sx={{
          color: current.color,
          borderColor: `${current.color}80`,
          '&:hover': { borderColor: current.color, bgcolor: `${current.color}14` },
          fontWeight: 600,
          letterSpacing: '0.04em',
        }}
      >
        {session.role}
      </Button>
      <Popover
        id={MENU_ID}
        open={open}
        anchorEl={anchor}
        onClose={() => setAnchor(null)}
        anchorOrigin={{ vertical: 'bottom', horizontal: 'right' }}
        transformOrigin={{ vertical: 'top', horizontal: 'right' }}
        slotProps={{ paper: { sx: { minWidth: 280, p: 2, mt: 1, borderRadius: 2 } } }}
      >
        <Typography
          variant="caption"
          sx={{ fontWeight: 700, letterSpacing: '0.08em', color: 'text.secondary', textTransform: 'uppercase' }}
        >
          Access control &amp; role
        </Typography>
        <Typography variant="caption" sx={{ color: 'text.secondary', display: 'block', mb: 1.5 }}>
          Controls mutation privileges
        </Typography>
        <Stack spacing={0.5}>
          {(Object.keys(ROLES) as Role[]).map((role) => {
            const item = ROLES[role]
            const selected = session.role === role
            return (
              <Box
                key={role}
                sx={{
                  display: 'flex',
                  alignItems: 'flex-start',
                  gap: 1.25,
                  p: 1,
                  borderRadius: 1.5,
                  color: item.color,
                  bgcolor: selected ? `${item.color}14` : 'transparent',
                }}
              >
                {item.icon}
                <Box sx={{ flexGrow: 1 }}>
                  <Typography variant="body2" sx={{ fontWeight: 700, color: 'text.primary' }}>
                    {item.label}
                  </Typography>
                  <Typography variant="caption" sx={{ color: 'text.secondary', display: 'block' }}>
                    {item.description}
                  </Typography>
                </Box>
                {selected && <CheckIcon />}
              </Box>
            )
          })}
        </Stack>
        <Typography variant="caption" sx={{ color: 'text.secondary', mt: 1, display: 'block' }}>
          Signed in as <strong>{session.username}</strong>
        </Typography>
      </Popover>
      <Button
        size="small"
        variant="text"
        color="inherit"
        sx={{ color: 'text.secondary', '&:hover': { color: 'error.main' }, textTransform: 'none', fontWeight: 500 }}
        onClick={() => endSession('signed-out')}
      >
        Sign out
      </Button>
    </Stack>
  )
}
