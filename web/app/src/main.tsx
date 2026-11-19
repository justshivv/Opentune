import { QueryClientProvider } from '@tanstack/react-query';
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { createHashRouter, RouterProvider } from 'react-router';
import { queryClient } from '@/lib/queryClient';
import { routes } from '@/routes';
import { playOpening } from '@/shell/opening';
import { useAppearance } from '@/state/appearance';
import '@/styles/global.css';

const router = createHashRouter(routes);

// The opening, as on the phone, once per load: left out with motion off in Settings or reduced by the system.
if (useAppearance.getState().motion && !window.matchMedia('(prefers-reduced-motion: reduce)').matches) playOpening();

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>
  </StrictMode>,
);
