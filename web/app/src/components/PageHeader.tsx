import { ChevronLeft } from 'lucide-react';
import { PrefetchLink } from './PrefetchLink';

/**
 * The page title row (report §1, `.header-nav`). A page inside another one
 * (Settings › Audio sources) names its parent above the title and links back.
 */
export function PageHeader({ title, back }: { title: string; back?: { to: string; label: string } }) {
  return (
    <div className="mx-(--body-gutter) pt-8">
      {back && (
        <PrefetchLink
          to={back.to}
          className="-ms-1.5 mb-1 inline-flex items-center gap-0.5 rounded-md py-1 pe-2 type-title-3 text-key transition-opacity duration-100 hover:opacity-75"
        >
          <ChevronLeft size={20} strokeWidth={2.25} />
          {back.label}
        </PrefetchLink>
      )}
      <h1 className="type-header-emphasized text-balance">{title}</h1>
    </div>
  );
}
