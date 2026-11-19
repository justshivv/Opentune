import { QueryClientProvider } from '@tanstack/react-query';
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { createHashRouter, RouterProvider } from 'react-router';
import { queryClient } from '@/lib/queryClient';
import { installStaleBuildRecovery } from '@/lib/staleBuild';
import { routes } from '@/routes';
import { playOpening } from '@/shell/opening';
import { useAppearance } from '@/state/appearance';
import '@/styles/global.css';

installStaleBuildRecovery();
const router = createHashRouter(routes);


createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>
  </StrictMode>,
);

// The opening's dive into the mark, once the player is up (index.html shows the mark meanwhile):
// left out with motion off in Settings or reduced by the system.
playOpening(useAppearance.getState().motion && !window.matchMedia('(prefers-reduced-motion: reduce)').matches);
