import { useEffect, useId, useRef, useState, type FormEvent } from "react";
import { SaveAlert, type Save } from "@/shared/api/useSave";
import {
  AlertDialog,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
  AlertDialogTrigger,
} from "@/shared/ui/alert-dialog";
import { Button } from "@/shared/ui/button";
import { Input } from "@/shared/ui/input";
import { Label } from "@/shared/ui/label";

/**
 * 동작 하나를 맡는 단추(FR-725·726·727). destructive 면 누를 때 묻고, 서버가 재인증을 물으면 같은 대화상자에 비밀번호 칸을 더한다.
 * 확인은 AlertDialogAction 이 아니라 폼의 제출 단추다 — Action 은 누르는 즉시 닫아(onOpenChange(false)) 응답 전에 대화상자가 사라지고,
 * 닫힘을 막으려 preventDefault 하면 폼 제출도 막힌다. open 은 이 부품이 쥔다.
 * 연 단추는 Trigger 다 — Radix 는 닫힐 때 Trigger 로만 초점을 돌린다.
 * save 는 이 단추만 쓴다(목록의 줄마다 useSave 를 따로) — 나눠 쓰면 재인증 403 에 모든 단추의 대화상자가 열린다.
 */
export function ConfirmButton<V>({
  label,
  title,
  description,
  destructive = false,
  save,
  values,
}: {
  label: string;
  title: string;
  description?: string;
  destructive?: boolean;
  save: Save<V>;
  values: V;
}) {
  const id = useId();
  const [asked, setAsked] = useState(false);
  const [missing, setMissing] = useState(false);
  const password = useRef<HTMLInputElement>(null);
  const open = asked || save.reauthNeeded;

  const close = () => {
    setAsked(false);
    setMissing(false);
  };

  // 성공했거나 재인증이 아닌 실패면 닫는다(실패 알림은 대화상자 밖에 있다)
  useEffect(() => {
    if (save.done || save.failure) close();
  }, [save.done, save.failure]);

  // 칸은 서버가 물을 때만 생긴다. 열려 있던 대화상자에 칸이 더해지면 여기서 옮긴다(새로 열 때는 onOpenAutoFocus)
  useEffect(() => {
    if (save.reauthNeeded) password.current?.focus();
  }, [save.reauthNeeded]);

  const submit = (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    if (!save.reauthNeeded) return save.save(values);
    const value = new FormData(e.currentTarget).get("password");
    if (typeof value !== "string" || value === "") {
      setMissing(true);
      password.current?.focus();
      return;
    }
    setMissing(false);
    save.reauth(value);
  };

  const error = missing ? "필수입니다" : save.reauthError;
  const busy = save.pending ? "처리 중…" : null;

  return (
    <>
      <AlertDialog
        open={open}
        onOpenChange={(next) => {
          if (next) return setAsked(true);
          // Esc·「취소」. 보내는 동안에는 닫지 않는다 — 늦게 온 재인증 403 이 다시 열게 된다
          if (save.pending) return;
          close();
          save.cancel();
        }}
      >
        <AlertDialogTrigger asChild>
          <Button
            type="button"
            variant={destructive ? "destructive" : "default"}
            aria-disabled={save.pending || undefined}
            onClick={(e) => {
              if (destructive) return;
              e.preventDefault(); // 열지 않고 바로 보낸다
              save.save(values);
            }}
          >
            {busy ?? label}
          </Button>
        </AlertDialogTrigger>
        <AlertDialogContent
          {...(description ? {} : { "aria-describedby": undefined })}
          onOpenAutoFocus={(e) => {
            // Radix 는 열 때 「취소」에 초점을 준다. 비밀번호 칸이 있으면 칸으로
            if (!password.current) return;
            e.preventDefault();
            password.current.focus();
          }}
        >
          <form onSubmit={submit} noValidate className="grid gap-4">
            <AlertDialogHeader>
              <AlertDialogTitle>{title}</AlertDialogTitle>
              {description && <AlertDialogDescription>{description}</AlertDialogDescription>}
            </AlertDialogHeader>
            {save.reauthNeeded && (
              <div className="space-y-1">
                <Label htmlFor={id}>관리자 비밀번호 재확인</Label>
                <Input
                  ref={password}
                  id={id}
                  name="password"
                  type="password"
                  autoComplete="current-password"
                  aria-invalid={error ? true : undefined}
                  aria-describedby={error ? `${id}-error` : undefined}
                />
                {error && (
                  <p id={`${id}-error`} role="alert" className="text-sm text-destructive">
                    {error}
                  </p>
                )}
              </div>
            )}
            <AlertDialogFooter>
              <AlertDialogCancel type="button">취소</AlertDialogCancel>
              <Button type="submit" variant={destructive ? "destructive" : "default"} aria-disabled={save.pending || undefined}>
                {busy ?? "확인"}
              </Button>
            </AlertDialogFooter>
          </form>
        </AlertDialogContent>
      </AlertDialog>
      <SaveAlert failure={save.failure} />
    </>
  );
}
