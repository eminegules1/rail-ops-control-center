import Box from '@mui/material/Box'
import Card from '@mui/material/Card'
import Skeleton from '@mui/material/Skeleton'
import Typography from '@mui/material/Typography'
import { useDashboardSummary } from '../../api/dashboard'
import { SEVERITY_COLORS } from '../../lib/colors'
import { formatCount } from '../../lib/format'

export function KpiCards() {
  const { data, isPending } = useDashboardSummary()

  const cards = [
    { label: 'Total events', caption: 'All events received', value: data?.totalEvents },
    { label: 'Open', caption: 'Status OPEN', value: data?.openEvents },
    {
      label: 'Critical',
      caption: 'CRITICAL and not resolved',
      value: data?.criticalEvents,
      accent: SEVERITY_COLORS.CRITICAL,
    },
  ]

  return (
    <Box sx={{ display: 'grid', gap: 2, gridTemplateColumns: { xs: '1fr', sm: 'repeat(3, 1fr)' } }}>
      {cards.map((card) => (
        <Card
          key={card.label}
          component="section"
          aria-label={card.label}
          aria-busy={isPending}
          sx={{ p: 2, borderLeft: card.accent ? `4px solid ${card.accent}` : undefined }}
        >
          <Typography variant="overline" color="text.secondary" component="h2" sx={{ lineHeight: 1.5 }}>
            {card.label}
          </Typography>
          <Typography sx={{ fontSize: '2rem', fontWeight: 600, lineHeight: 1.2 }}>
            {isPending ? <Skeleton width={80} /> : card.value === undefined ? '—' : formatCount(card.value)}
          </Typography>
          <Typography variant="caption" color="text.secondary">
            {card.caption}
          </Typography>
        </Card>
      ))}
    </Box>
  )
}
