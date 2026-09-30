import AppBar from '@mui/material/AppBar'
import Box from '@mui/material/Box'
import Divider from '@mui/material/Divider'
import Drawer from '@mui/material/Drawer'
import List from '@mui/material/List'
import ListItemButton from '@mui/material/ListItemButton'
import ListItemText from '@mui/material/ListItemText'
import Toolbar from '@mui/material/Toolbar'
import Typography from '@mui/material/Typography'
import { NavLink, Outlet } from 'react-router'
import { ConnectionChip } from './ConnectionChip'
import { ThemeSwitch } from './ThemeSwitch'
import { UserMenu } from './UserMenu'

const NAV_WIDTH = 200

const NAV_ITEMS = [
  { to: '/dashboard', label: 'Dashboard' },
  { to: '/events', label: 'Events' },
  { to: '/services', label: 'Services' },
]

export function AppLayout() {
  return (
    <Box sx={{ display: 'flex', minHeight: '100vh' }}>
      <AppBar position="fixed" color="default" sx={{ zIndex: (theme) => theme.zIndex.drawer + 1 }}>
        <Toolbar variant="dense" sx={{ gap: 2 }}>
          <Typography component="div" variant="subtitle1" sx={{ fontWeight: 600, flexGrow: 1 }}>
            Rail Ops Control Center
          </Typography>
          <ConnectionChip />
          <ThemeSwitch />
          <Divider orientation="vertical" flexItem sx={{ mx: 1, my: 1 }} />
          <UserMenu />
        </Toolbar>
      </AppBar>
      <Drawer
        variant="permanent"
        sx={{
          width: NAV_WIDTH,
          flexShrink: 0,
          '& .MuiDrawer-paper': { width: NAV_WIDTH, boxSizing: 'border-box' },
        }}
      >
        <Toolbar variant="dense" />
        <Box component="nav" aria-label="Main">
          <List dense>
            {NAV_ITEMS.map((item) => (
              // NavLink sets aria-current="page" and the "active" class on the current route.
              <ListItemButton
                key={item.to}
                component={NavLink}
                to={item.to}
                sx={{
                  '&.active': { bgcolor: 'action.selected', fontWeight: 600 },
                }}
              >
                <ListItemText primary={item.label} />
              </ListItemButton>
            ))}
          </List>
        </Box>
      </Drawer>
      <Box component="main" sx={{ flexGrow: 1, minWidth: 0, p: 2 }}>
        <Toolbar variant="dense" />
        <Outlet />
      </Box>
    </Box>
  )
}
