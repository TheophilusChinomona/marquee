import { useCallback, useEffect, useMemo, useRef } from 'react';

/**
 * Tracks a monotonically increasing request token so stale async responses
 * from earlier queries or previous screens/profiles are safely ignored.
 */
export function useLatestRequest() {
  const tokenRef = useRef(0);
  const mountedRef = useRef(true);

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
      tokenRef.current += 1;
    };
  }, []);

  const begin = useCallback(() => {
    tokenRef.current += 1;
    return tokenRef.current;
  }, []);

  const isCurrent = useCallback((token: number) => {
    return mountedRef.current && tokenRef.current === token;
  }, []);

  const invalidate = useCallback(() => {
    tokenRef.current += 1;
  }, []);

  return useMemo(() => ({ begin, isCurrent, invalidate }), [begin, isCurrent, invalidate]);
}
