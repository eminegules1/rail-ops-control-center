import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'

const POSTER = '/login_page.jpg'

/**
 * The login page's left half: a looping background video under a deep navy gradient, with the product name over it.
 * It is decoration, so the video is hidden from assistive technology. For a visitor who asks for reduced motion the
 * video is not shown at all and the poster image stays.
 */
export function BrandPanel() {
  return (
    <Box
      sx={{
        position: 'relative',
        overflow: 'hidden',
        display: 'flex',
        alignItems: 'flex-end',
        p: 6,
        color: '#fff',
        bgcolor: '#0a192f',
        backgroundImage: `url(${POSTER})`,
        backgroundSize: 'cover',
        backgroundPosition: 'center',
      }}
    >
      <Box
        component="video"
        src="/Alstom_History_Innovation.mp4"
        poster={POSTER}
        autoPlay
        muted
        loop
        playsInline
        aria-hidden
        tabIndex={-1}
        sx={{
          position: 'absolute',
          inset: 0,
          width: '100%',
          height: '100%',
          objectFit: 'cover',
          '@media (prefers-reduced-motion: reduce)': { display: 'none' },
        }}
      />
      <Box
        aria-hidden
        sx={{
          position: 'absolute',
          inset: 0,
          background: 'linear-gradient(to top, rgba(10, 25, 47, 0.85) 0%, rgba(0, 32, 91, 0.25) 50%, rgba(0, 0, 0, 0.05) 100%)',
        }}
      />
      <Box sx={{ position: 'relative', maxWidth: 520 }}>
        <Typography component="p" sx={{ fontSize: '2.25rem', fontWeight: 700, lineHeight: 1.15 }}>
          Alstom Rail Operations Control Center
        </Typography>
        <Typography component="p" sx={{ mt: 2, fontSize: '1.125rem', opacity: 0.9 }}>
          Real-time incident monitoring &amp; fleet telemetry.
        </Typography>
      </Box>
    </Box>
  )
}
