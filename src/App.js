import React from 'react';
import logo from './logo.svg';
import Background from './images/background.png'
import { createTheme, CssBaseline, makeStyles, TextField } from '@material-ui/core';
import { ThemeProvider } from '@material-ui/styles';

const darkTheme = createTheme({
  palette: {
    type: 'dark',
  }
});



function App() {
  return (
    <ThemeProvider theme={darkTheme}>
      <CssBaseline />
      <div class="page-bg"/>
    </ThemeProvider>
  );
}

export default App;
