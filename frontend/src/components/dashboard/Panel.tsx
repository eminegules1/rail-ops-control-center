import Alert from '@mui/material/Alert'
import Card from '@mui/material/Card'
import Skeleton from '@mui/material/Skeleton'
import Typography from '@mui/material/Typography'
import { useId } from 'react'
import type { ReactNode } from 'react'

type Props = {
  title: string
  /** First load in progress: show a skeleton of this height. */
  loading: boolean
  /** Load failed and there is no earlier data to keep showing. */
  failed: boolean
  skeletonHeight: number
  /** Replaces the default "Couldn't load …" message, e.g. with the API's error detail. */
  errorText?: string
  children?: ReactNode
}

/** A titled dashboard section with its loading and error states. */
export function Panel({ title, loading, failed, skeletonHeight, errorText, children }: Props) {
  const titleId = useId()

  return (
    <Card component="section" aria-labelledby={titleId} aria-busy={loading} sx={{ p: 2, minWidth: 0 }}>
      <Typography id={titleId} variant="h2" gutterBottom>
        {title}
      </Typography>
      {loading ? (
        <Skeleton variant="rounded" height={skeletonHeight} />
      ) : failed ? (
        <Alert severity="error" variant="outlined">
          {errorText ?? `Couldn't load ${title.toLowerCase()}.`}
        </Alert>
      ) : (
        children
      )}
    </Card>
  )
}

export function EmptyState({ children }: { children: ReactNode }) {
  return (
    <Typography color="text.secondary" sx={{ py: 2 }}>
      {children}
    </Typography>
  )
}
