import { QueryClientProvider } from '@tanstack/react-query';
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { createHashRouter, RouterProvider } from 'react-router';
import { queryClient } from '@/lib/queryClient';
import { installStaleBuildRecovery } from '@/lib/staleBuild';
import { routes } from '@/routes';
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
