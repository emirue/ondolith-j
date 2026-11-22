import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useNavigate } from "react-router";
import { describe, expect, it } from "vitest";
import { renderWithQuery } from "@/test-api";
import { useNotice } from "./notice";

function Probe() {
  const notice = useNotice();
  const navigate = useNavigate();
  return (
    <>
      <button onClick={() => notice.show("저장했습니다")}>알림</button>
      <button onClick={() => navigate("/b")}>다른 곳</button>
      <button onClick={() => navigate("/a")}>돌아옴</button>
    </>
  );
}

describe("알림 칸", () => {
  it("처음부터 빈 role=status 칸이 있다", () => {
    renderWithQuery(<Probe />, "/a", { notice: true });
    const notice = screen.getByTestId("notice");
    expect(notice).toHaveAttribute("role", "status");
    expect(notice).toBeEmptyDOMElement();
  });

  it("show 하면 그 글이 보이고, 다른 경로에 갔다 돌아오면 빈 칸", async () => {
    renderWithQuery(<Probe />, "/a", { notice: true });
    const u = userEvent.setup();
    await u.click(screen.getByRole("button", { name: "알림" }));
    expect(screen.getByTestId("notice")).toHaveTextContent("저장했습니다");
    await u.click(screen.getByRole("button", { name: "다른 곳" }));
    await u.click(screen.getByRole("button", { name: "돌아옴" }));
    expect(screen.getByTestId("notice")).toBeEmptyDOMElement();
  });
});
