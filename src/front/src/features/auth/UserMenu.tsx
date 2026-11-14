import { Link } from "react-router";
import { Button } from "@/shared/ui/button";
import { useLogout, useMe } from "./useMe";

/** 머리글의 로그인 상태. 표시일 뿐 보안 판정이 아니다. */
export function UserMenu() {
  const me = useMe();
  const logout = useLogout();
  const user = me.data?.user;
  if (!user) return <Link to="/login" className="text-sm underline">로그인</Link>;
  return (
    <div className="flex items-center gap-2 text-sm">
      <span>{user.email}</span>
      <Button type="button" variant="outline" size="sm" onClick={logout}>로그아웃</Button>
    </div>
  );
}
