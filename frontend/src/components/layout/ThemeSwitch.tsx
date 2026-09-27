import ToggleButton from '@mui/material/ToggleButton'
import ToggleButtonGroup from '@mui/material/ToggleButtonGroup'
import { useColorScheme } from '@mui/material/styles'

type Mode = 'light' | 'dark' | 'system'

const OPTIONS: { value: Mode; label: string }[] = [
  { value: 'light', label: 'Light' },
  { value: 'dark', label: 'Dark' },
  { value: 'system', label: 'System' },
]

/** Light / Dark / System; MUI remembers the choice in localStorage. */
export function ThemeSwitch() {
  const { mode, setMode } = useColorScheme()
  // mode is undefined until MUI has read the stored choice.
  if (!mode) return null

  return (
    <ToggleButtonGroup
      value={mode}
      exclusive
      size="small"
      aria-label="Theme"
      onChange={(_, value: Mode | null) => {
        if (value) setMode(value)
      }}
      sx={{ bgcolor: 'background.paper' }}
    >
      {OPTIONS.map((option) => (
        <ToggleButton key={option.value} value={option.value} sx={{ px: 1.5, py: 0.25 }}>
          {option.label}
        </ToggleButton>
      ))}
    </ToggleButtonGroup>
  )
}
