import { createTheme } from '@mui/material/styles'

// Light and dark schemes behind a class on <html>, so the user's theme choice
// can override the OS preference (see ThemeSwitch).
export const theme = createTheme({
  colorSchemes: { light: true, dark: true },
  cssVariables: { colorSchemeSelector: 'class' },
  shape: { borderRadius: 6 },
  typography: {
    fontSize: 13,
    h1: { fontSize: '1.5rem', fontWeight: 600 },
    h2: { fontSize: '1rem', fontWeight: 600 },
  },
  components: {
    MuiCard: { defaultProps: { variant: 'outlined' } },
    MuiPaper: { defaultProps: { elevation: 0 } },
  },
})
