import KpiBar from "./components/KpiBar";
import TacticalMap from "./components/TacticalMap";

function App() {
  return (
    <div className="flex h-screen flex-col">
      <header className="flex items-center gap-2 border-b border-slate-800 bg-slate-950 px-4 py-2">
        <span className="text-sm font-semibold tracking-wide text-slate-100">
          SwarmHQ
        </span>
        <span className="text-xs text-slate-500">tactical map</span>
        <KpiBar />
      </header>
      <main className="flex-1">
        <TacticalMap />
      </main>
    </div>
  );
}

export default App;
