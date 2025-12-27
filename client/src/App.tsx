import { BrowserRouter as Router, Routes, Route } from 'react-router-dom';
import Home from './pages/Home';
import CallRoom from './pages/CallRoom';

function App() {
  return (
    <Router>
      <Routes>
        <Route path="/" element={<Home />} />
        <Route path="/call/:roomId" element={<CallRoom />} />
      </Routes>
    </Router>
  );
}

export default App;
