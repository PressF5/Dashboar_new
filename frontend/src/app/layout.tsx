import type { Metadata } from "next";
import "./globals.css";
export const metadata: Metadata = { title: "ТОР «Моя школа» — статистика", description: "Показатели цифровой образовательной среды по МСУ и образовательным организациям" };
export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) { return <html lang="ru"><body>{children}</body></html>; }
