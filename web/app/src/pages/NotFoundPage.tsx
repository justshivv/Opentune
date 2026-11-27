import { PageHeader } from '@/components/PageHeader';
import { PrefetchLink } from '@/components/PrefetchLink';

export default function NotFoundPage() {
  return (
    <>
      <PageHeader title="Page not found" />
      <p className="mx-(--body-gutter) my-2 type-title-3 text-secondary">
        This page doesn’t exist. <PrefetchLink to="/home" className="text-key transition-opacity hover:opacity-75">Go to Home</PrefetchLink>
      </p>
    </>
  );
}
