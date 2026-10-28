import type { ComponentType } from 'react';
import { BrowserRouter, Navigate, Route, Routes, useParams } from 'react-router';
import { AccountUpdate } from './screens/AccountUpdate';
import { AccountView } from './screens/AccountView';
import { BillPayment } from './screens/BillPayment';
import { CardList } from './screens/CardList';
import { CardUpdate } from './screens/CardUpdate';
import { CardView } from './screens/CardView';
import { Menu } from './screens/Menu';
import { NotAvailable } from './screens/NotAvailable';
import { SignOn } from './screens/SignOn';
import { SignedOff } from './screens/SignedOff';
import { TransactionAdd } from './screens/TransactionAdd';
import { TransactionList } from './screens/TransactionList';
import { TransactionReports } from './screens/TransactionReports';
import { TransactionView } from './screens/TransactionView';
import { UserAdd } from './screens/UserAdd';
import { UserDelete } from './screens/UserDelete';
import { UserList } from './screens/UserList';
import { UserUpdate } from './screens/UserUpdate';
import { SessionContext, useSessionTracker } from './session';

const REGISTRY: Record<string, ComponentType> = {
  COSGN00C: SignOn,
  COMEN01C: () => <Menu program="COMEN01C" />,
  COADM01C: () => <Menu program="COADM01C" />,
  COBIL00C: BillPayment,
  COUSR00C: UserList,
  COUSR01C: UserAdd,
  COUSR02C: UserUpdate,
  COUSR03C: UserDelete,
  COCRDLIC: CardList,
  COCRDSLC: CardView,
  COCRDUPC: CardUpdate,
  COACTVWC: AccountView,
  COACTUPC: AccountUpdate,
  COTRN00C: TransactionList,
  COTRN01C: TransactionView,
  COTRN02C: TransactionAdd,
  CORPT00C: TransactionReports,
};

function ScreenRoute() {
  const { program = '' } = useParams();
  const Screen = REGISTRY[program];
  // key: a new program is a new screen instance (fresh first entry, carried message read once).
  return Screen ? <Screen key={program} /> : <NotAvailable key={program} program={program} />;
}

function Root() {
  const user = useSessionTracker();
  return (
    <SessionContext.Provider value={user}>
      <Routes>
        <Route path="/" element={<Navigate to="/screens/COSGN00C" replace />} />
        <Route path="/signed-off" element={<SignedOff />} />
        <Route path="/screens/:program" element={<ScreenRoute />} />
        <Route path="*" element={<Navigate to="/screens/COSGN00C" replace />} />
      </Routes>
    </SessionContext.Provider>
  );
}

export function App() {
  return (
    <BrowserRouter>
      <Root />
    </BrowserRouter>
  );
}
