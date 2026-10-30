import { ArrowUpRight, ChevronsUpDown, LogIn, RotateCw } from 'lucide-react';
import { useCallback, useState } from 'react';
import { Menu, MenuHeader, MenuItem, MenuSeparator } from '@/components/Menu';
import { useRefreshYtAuth, useSelectChannel, useSetConnected, useYtAuth } from '@/extension/useYtAuth';

const SIGN_IN_URL = 'https://accounts.google.com/ServiceLogin?service=youtube&continue=https://music.youtube.com/';

// The signed-out pill: Apple's red "Sign In" (report §1, .navigation__native-cta).
const pill =
  'self-start rounded-[16px] bg-key-bg px-3.5 py-[5px] type-[600_13px/18px] text-white hover:brightness-[1.08] disabled:opacity-60';
// The folded sidebar's version: the same action as a round icon, its words in the tooltip.
const roundPill = 'grid size-9 place-items-center self-center rounded-full bg-key-bg text-white hover:brightness-[1.08] disabled:opacity-60';
const avatar = 'inline-grid flex-none place-items-center rounded-full bg-quinary object-cover type-[600_11px/1] text-secondary';

function Avatar({ src, name, size }: { src: string | null; name: string; size: number }) {
  return src ? (
    <img className={avatar} src={src} alt="" width={size} height={size} referrerPolicy="no-referrer" />
  ) : (
    <span className={avatar} style={{ width: size, height: size }} aria-hidden="true">
      {name.slice(0, 1).toUpperCase()}
    </span>
  );
}

/**
 * The sidebar's account control (report §1: avatar and name at the bottom,
 * opening the account menu; a red "Sign In" pill when signed out).
 *
 * There is no sign-in form (prompt §5.3): OpenTune uses the YouTube session
 * this browser already has, through the extension. "Sign In" opens Google's
 * own sign-in; the extension notices the new session and this updates itself.
 */
export function AccountButton({ compact = false }: { compact?: boolean }) {
  const status = useYtAuth();
  const select = useSelectChannel();
  const connect = useSetConnected();
  const refresh = useRefreshYtAuth();
  const [anchor, setAnchor] = useState<HTMLElement | null>(null);
  const close = useCallback(() => setAnchor(null), []);

  if (status.state !== 'ready') return null;
  const { auth } = status;

  if (!auth.browserSignedIn) {
    return (
      <a className={compact ? roundPill : pill} href={SIGN_IN_URL} target="_blank" rel="noreferrer" aria-label={compact ? 'Sign In' : undefined} title={compact ? 'Sign In' : undefined}>
        {compact ? <LogIn size={17} strokeWidth={2.2} /> : 'Sign In'}
      </a>
    );
  }
  if (!auth.connected) {
    const label = 'Connect YouTube Music';
    return (
      <button className={compact ? roundPill : pill} onClick={() => connect.mutate(true)} disabled={connect.isPending} aria-label={compact ? label : undefined} title={compact ? label : undefined}>
        {compact ? <LogIn size={17} strokeWidth={2.2} /> : label}
      </button>
    );
  }
  if (!auth.active) {
    // Signed in to the browser, but the account couldn't be read (the reason is on the Extension page).
    const label = refresh.isPending ? 'Reading account…' : 'Retry YouTube Sign-In';
    return (
      <button
        className={compact ? roundPill : pill}
        title={compact ? label : (auth.problem ?? undefined)}
        aria-label={compact ? label : undefined}
        onClick={() => refresh.mutate()}
        disabled={refresh.isPending}
      >
        {compact ? <RotateCw size={16} strokeWidth={2.2} /> : label}
      </button>
    );
  }
  const active = auth.active;
  const multiple = auth.accounts.flatMap((a) => a.channels).length > 1;

  return (
    <>
      <button
        // Signed in: a 24px avatar and the name, opening the account menu.
        className={
          compact
            ? 'grid size-10 place-items-center self-center rounded-nav hover:bg-quinary aria-expanded:bg-quinary'
            : 'flex w-full min-w-0 items-center gap-2 rounded-nav px-1 py-1.5 text-start type-body-reduced text-primary hover:bg-quinary aria-expanded:bg-quinary'
        }
        aria-haspopup="menu"
        aria-expanded={anchor !== null}
        aria-label={compact ? `Account: ${active.name}` : undefined}
        title={compact ? active.name : undefined}
        onClick={(event) => setAnchor(anchor ? null : event.currentTarget)}
      >
        <Avatar src={active.avatarUrl} name={active.name} size={compact ? 28 : 24} />
        {!compact && <span className="min-w-0 flex-1 truncate">{active.name}</span>}
        {!compact && multiple && <ChevronsUpDown size={14} strokeWidth={2} className="flex-none text-secondary" />}
      </button>
      {anchor && (
        <Menu anchor={anchor} placement="above-start" onClose={close} label="Account">
          {auth.accounts.map((account) => (
            <div key={account.authUser} role="group" aria-label={account.email ?? account.name}>
              <MenuHeader>{account.email ?? account.name}</MenuHeader>
              {account.channels.map((channel) => (
                <MenuItem
                  key={channel.id}
                  checked={channel.id === active.id}
                  icon={<Avatar src={channel.avatarUrl} name={channel.name} size={20} />}
                  trailing={channel.brand ? 'Brand' : undefined}
                  onSelect={() => {
                    close();
                    if (channel.id !== active.id) select.mutate(channel.id);
                  }}
                >
                  {channel.name}
                </MenuItem>
              ))}
            </div>
          ))}
          <MenuSeparator />
          <MenuItem
            onSelect={() => {
              close();
              window.open(SIGN_IN_URL.replace('ServiceLogin', 'AddSession'), '_blank', 'noreferrer');
            }}
            trailing={<ArrowUpRight size={13} />}
          >
            Add Account
          </MenuItem>
          <MenuItem
            destructive
            onSelect={() => {
              close();
              connect.mutate(false);
            }}
          >
            Disconnect from OpenTune
          </MenuItem>
        </Menu>
      )}
    </>
  );
}

